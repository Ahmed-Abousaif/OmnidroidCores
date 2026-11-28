#include "omni_ra.h"
#include "libretro.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ASSERT_TRUE(cond)                                                          \
  do {                                                                             \
    if (!(cond)) {                                                                 \
      fprintf(stderr, "FAIL %s:%d: condition false: %s\n", __FILE__, __LINE__, #cond); \
      exit(1);                                                                     \
    }                                                                              \
  } while (0)

#define ASSERT_EQ(a, b)                                                            \
  do {                                                                             \
    long long _va = (long long)(a);                                                \
    long long _vb = (long long)(b);                                                \
    if (_va != _vb) {                                                              \
      fprintf(stderr, "FAIL %s:%d: %s (%lld) != %s (%lld)\n",                      \
              __FILE__, __LINE__, #a, _va, #b, _vb);                               \
      exit(1);                                                                     \
    }                                                                              \
  } while (0)

#define ASSERT_STR_EQ(a, b)                                                        \
  do {                                                                             \
    const char* _sa = (a);                                                         \
    const char* _sb = (b);                                                         \
    if (!_sa || !_sb || strcmp(_sa, _sb) != 0) {                                   \
      fprintf(stderr, "FAIL %s:%d: '%s' != '%s'\n",                                \
              __FILE__, __LINE__, _sa ? _sa : "(null)", _sb ? _sb : "(null)");     \
      exit(1);                                                                     \
    }                                                                              \
  } while (0)

typedef struct fake_http_entry_s {
  uint64_t request_id;
  char url[512];
  char post_data[1024];
  char user_agent[256];
} fake_http_entry_t;

typedef struct fake_event_record_s {
  uint32_t type;
  uint32_t id;
  char title[128];
  char extra[128];
} fake_event_record_t;

typedef struct fake_host_ctx_s {
  uint64_t now_ms;
  uint8_t ram[4096];
  const uint8_t* vfs_data;
  size_t vfs_len;
  const char* vfs_path;

  int defer_http;
  int fail_award_remaining;
  fake_http_entry_t queue[32];
  int queue_count;

  fake_event_record_t events[64];
  int event_count;

  int last_async_result;
  int async_calls;
  char last_error[256];
  char last_user_agent[256];
} fake_host_ctx_t;

typedef struct fake_file_s {
  const uint8_t* data;
  size_t len;
  size_t pos;
} fake_file_t;

static uint64_t host_get_time_ms(void* userdata) {
  return ((fake_host_ctx_t*)userdata)->now_ms;
}

static uint32_t host_read_memory(void* userdata, uint32_t address, uint8_t* buffer, uint32_t num_bytes) {
  fake_host_ctx_t* ctx = (fake_host_ctx_t*)userdata;
  if (address >= sizeof(ctx->ram))
    return 0;
  uint32_t avail = (uint32_t)(sizeof(ctx->ram) - address);
  uint32_t to_read = num_bytes < avail ? num_bytes : avail;
  memcpy(buffer, ctx->ram + address, to_read);
  return to_read;
}

static void* host_vfs_open(void* userdata, const char* path_utf8) {
  fake_host_ctx_t* ctx = (fake_host_ctx_t*)userdata;
  if (!ctx->vfs_data || !ctx->vfs_path || strcmp(path_utf8, ctx->vfs_path) != 0)
    return NULL;
  fake_file_t* f = (fake_file_t*)calloc(1, sizeof(*f));
  f->data = ctx->vfs_data;
  f->len = ctx->vfs_len;
  f->pos = 0;
  return f;
}

static void host_vfs_seek(void* userdata, void* file_handle, int64_t offset, int32_t origin) {
  (void)userdata;
  fake_file_t* f = (fake_file_t*)file_handle;
  int64_t next = (int64_t)f->pos;
  if (origin == SEEK_SET)
    next = offset;
  else if (origin == SEEK_CUR)
    next += offset;
  else if (origin == SEEK_END)
    next = (int64_t)f->len + offset;
  if (next < 0)
    next = 0;
  if ((size_t)next > f->len)
    next = (int64_t)f->len;
  f->pos = (size_t)next;
}

static int64_t host_vfs_tell(void* userdata, void* file_handle) {
  (void)userdata;
  return (int64_t)((fake_file_t*)file_handle)->pos;
}

static uint32_t host_vfs_read(void* userdata, void* file_handle, void* buffer, uint32_t requested_bytes) {
  (void)userdata;
  fake_file_t* f = (fake_file_t*)file_handle;
  size_t avail = f->len - f->pos;
  size_t n = requested_bytes < avail ? requested_bytes : avail;
  if (n > 0) {
    memcpy(buffer, f->data + f->pos, n);
    f->pos += n;
  }
  return (uint32_t)n;
}

static void host_vfs_close(void* userdata, void* file_handle) {
  (void)userdata;
  free(file_handle);
}

static void host_on_event(void* userdata, const omni_ra_event_t* ev) {
  fake_host_ctx_t* ctx = (fake_host_ctx_t*)userdata;
  if (ctx->event_count >= 64)
    return;
  fake_event_record_t* rec = &ctx->events[ctx->event_count++];
  memset(rec, 0, sizeof(*rec));
  rec->type = ev->type;
  if (ev->achievement) {
    rec->id = ev->achievement->id;
    snprintf(rec->title, sizeof(rec->title), "%s", ev->achievement->title ? ev->achievement->title : "");
  } else if (ev->leaderboard) {
    rec->id = ev->leaderboard->id;
    snprintf(rec->title, sizeof(rec->title), "%s", ev->leaderboard->title ? ev->leaderboard->title : "");
  } else if (ev->leaderboard_scoreboard) {
    rec->id = ev->leaderboard_scoreboard->leaderboard_id;
    snprintf(rec->extra, sizeof(rec->extra), "%s",
             ev->leaderboard_scoreboard->submitted_score ? ev->leaderboard_scoreboard->submitted_score : "");
  } else if (ev->server_error) {
    snprintf(rec->extra, sizeof(rec->extra), "%s",
             ev->server_error->error_message ? ev->server_error->error_message : "");
  }
}

static void host_log_msg(void* userdata, int32_t level, const char* message) {
  (void)userdata;
  (void)level;
  (void)message;
}

static const char* kPatchJson =
    "{\"Success\":true,\"GameId\":1234,\"Title\":\"Fixture Quest\",\"ConsoleId\":25,"
    "\"ImageIconUrl\":\"https://media.retroachievements.org/Images/112233.png\","
    "\"RichPresenceGameId\":1234,\"RichPresencePatch\":\"Display:\\r\\nStage @Number(0xH0003)\\r\\n\","
    "\"Sets\":[{"
      "\"AchievementSetId\":1111,\"GameId\":1234,\"Title\":null,\"Type\":\"core\","
      "\"ImageIconUrl\":\"https://media.retroachievements.org/Images/112233.png\","
      "\"Achievements\":["
        "{\"ID\":5501,\"Title\":\"First Step\",\"Description\":\"Reach stage 3\",\"Flags\":3,\"Points\":5,"
        "\"MemAddr\":\"0xH0001=3\",\"Author\":\"Tester\",\"BadgeName\":\"00234\","
        "\"Rarity\":80.5,\"RarityHardcore\":60.2,\"Type\":\"progression\","
        "\"Created\":1367266583,\"Modified\":1376929305}"
      "],"
      "\"Leaderboards\":["
        "{\"ID\":4401,\"Title\":\"High Score\",\"Description\":\"Best run\","
        "\"Mem\":\"STA:0xH000A=1::CAN:0xH000B=1::SUB:0xH000C=1::VAL:0xH000D\",\"Format\":\"SCORE\"}"
      "]"
    "}]}";

static const char* build_fixture_response(fake_host_ctx_t* ctx, const char* post_data, int32_t* out_status) {
  *out_status = 200;
  if (strstr(post_data, "r=login2")) {
    if (strstr(post_data, "p=bad")) {
      *out_status = 401;
      return "{\"Success\":false,\"Error\":\"Invalid User/Password combination. Please try again\",\"Code\":\"invalid_credentials\"}";
    }
    return "{\"Success\":true,\"User\":\"PlayerOne\",\"DisplayName\":\"Player One\",\"Token\":\"Tok12345\",\"Score\":500,\"SoftcoreScore\":120,\"Messages\":1,\"AvatarUrl\":\"https://media.retroachievements.org/UserPic/PlayerOne.png\"}";
  }
  if (strstr(post_data, "r=gameid")) {
    return "{\"Success\":true,\"GameID\":1234}";
  }
  if (strstr(post_data, "r=achievementsets")) {
    return kPatchJson;
  }
  if (strstr(post_data, "r=startsession")) {
    return "{\"Success\":true,\"ServerNow\":1700000000,\"HardcoreUnlocks\":[],\"Unlocks\":[]}";
  }
  if (strstr(post_data, "r=ping")) {
    return "{\"Success\":true}";
  }
  if (strstr(post_data, "r=awardachievement")) {
    if (ctx->fail_award_remaining > 0) {
      ctx->fail_award_remaining--;
      *out_status = OMNI_RA_HTTP_RETRYABLE_CLIENT_ERROR;
      return "";
    }
    return "{\"Success\":true,\"Score\":505,\"SoftcoreScore\":120,\"AchievementID\":5501,\"AchievementsRemaining\":0}";
  }
  if (strstr(post_data, "r=submitlbentry")) {
    return "{\"Success\":true,\"Response\":{\"Score\":42,\"BestScore\":42,\"Rank\":3,\"NumEntries\":10,\"TopEntries\":[{\"User\":\"PlayerOne\",\"Rank\":1,\"Score\":99}]}}";
  }
  return "{\"Success\":true}";
}

static void host_http_request(void* userdata, uint64_t request_id, const omni_ra_http_request_t* req) {
  fake_host_ctx_t* ctx = (fake_host_ctx_t*)userdata;
  if (req->user_agent)
    snprintf(ctx->last_user_agent, sizeof(ctx->last_user_agent), "%s", req->user_agent);

  ASSERT_TRUE(ctx->queue_count < 32);
  fake_http_entry_t* slot = &ctx->queue[ctx->queue_count++];
  slot->request_id = request_id;
  snprintf(slot->url, sizeof(slot->url), "%s", req->url ? req->url : "");
  snprintf(slot->post_data, sizeof(slot->post_data), "%s", req->post_data ? req->post_data : "");
  snprintf(slot->user_agent, sizeof(slot->user_agent), "%s", req->user_agent ? req->user_agent : "");
}

static void flush_http(omni_ra_t* ra, fake_host_ctx_t* ctx) {
  while (ctx->queue_count > 0) {
    fake_http_entry_t batch[32];
    int n = ctx->queue_count;
    memcpy(batch, ctx->queue, (size_t)n * sizeof(fake_http_entry_t));
    ctx->queue_count = 0;

    for (int i = 0; i < n; ++i) {
      int32_t status = 200;
      const char* body = build_fixture_response(ctx, batch[i].post_data, &status);
      omni_ra_http_response_t resp;
      memset(&resp, 0, sizeof(resp));
      resp.struct_size = (uint32_t)sizeof(resp);
      resp.http_status = status;
      resp.body = body;
      resp.body_len = (uint32_t)strlen(body);
      omni_ra_http_complete(ra, batch[i].request_id, &resp);
    }
  }
}

static void async_cb(void* cb_userdata, int32_t result, const char* err) {
  fake_host_ctx_t* ctx = (fake_host_ctx_t*)cb_userdata;
  ctx->async_calls++;
  ctx->last_async_result = result;
  snprintf(ctx->last_error, sizeof(ctx->last_error), "%s", err ? err : "");
}

static omni_ra_host_t make_host(fake_host_ctx_t* ctx, const omni_ra_file_callbacks_t* fcb) {
  omni_ra_host_t h;
  memset(&h, 0, sizeof(h));
  h.struct_size = (uint32_t)sizeof(h);
  h.host_abi_version = omni_ra_abi_version();
  h.userdata = ctx;
  h.get_time_ms = host_get_time_ms;
  h.http_request = host_http_request;
  h.on_event = host_on_event;
  h.read_memory = host_read_memory;
  h.log_message = host_log_msg;
  h.file_callbacks = fcb;
  return h;
}

static void test_abi_version_and_struct_sizes(void) {
  uint32_t ver = omni_ra_abi_version();
  ASSERT_EQ(OMNI_RA_ABI_MAJOR(ver), 1);
  ASSERT_EQ(OMNI_RA_ABI_MINOR(ver), 0);

  omni_ra_build_info_t info;
  memset(&info, 0, sizeof(info));
  info.struct_size = (uint32_t)sizeof(info);
  ASSERT_EQ(omni_ra_get_build_info(&info), OMNI_RA_OK);
  ASSERT_EQ(info.abi_major, 1);
  ASSERT_STR_EQ(info.rcheevos_version, "12.5.0");

  /* Simulate an older host struct lacking optional trailing fields */
  typedef struct older_build_info_s {
    uint32_t struct_size;
    uint32_t abi_major;
    uint32_t abi_minor;
  } older_build_info_t;
  older_build_info_t old_info;
  memset(&old_info, 0, sizeof(old_info));
  old_info.struct_size = (uint32_t)sizeof(old_info);
  ASSERT_EQ(omni_ra_get_build_info((omni_ra_build_info_t*)&old_info), OMNI_RA_OK);
  ASSERT_EQ(old_info.abi_major, 1);

  /* Oversized newer host struct */
  typedef struct newer_host_s {
    omni_ra_host_t base;
    uint64_t future_field;
  } newer_host_t;
  fake_host_ctx_t ctx;
  memset(&ctx, 0, sizeof(ctx));
  newer_host_t nh;
  memset(&nh, 0, sizeof(nh));
  nh.base = make_host(&ctx, NULL);
  nh.base.struct_size = (uint32_t)sizeof(nh);
  nh.future_field = 0xDEADBEEFull;
  omni_ra_t* ra = omni_ra_create(&nh.base);
  ASSERT_TRUE(ra != NULL);
  omni_ra_destroy(ra);

  /* Reject incompatible major ABI version */
  omni_ra_host_t bad = make_host(&ctx, NULL);
  bad.host_abi_version = OMNI_RA_MAKE_ABI_VERSION(99, 0);
  ASSERT_TRUE(omni_ra_create(&bad) == NULL);

  /* Reject older host missing required on_event */
  bad = make_host(&ctx, NULL);
  bad.struct_size = (uint32_t)offsetof(omni_ra_host_t, on_event);
  ASSERT_TRUE(omni_ra_create(&bad) == NULL);
}

static void test_login_load_trigger_retry_hardcore_progress_and_multi_instance(void) {
  fake_host_ctx_t ctx;
  memset(&ctx, 0, sizeof(ctx));
  ctx.now_ms = 1000;

  static const uint8_t kAtariRom[16] = {
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
  };
  ctx.vfs_data = kAtariRom;
  ctx.vfs_len = sizeof(kAtariRom);
  ctx.vfs_path = "/roms/fixture.a26";

  omni_ra_file_callbacks_t fcb;
  memset(&fcb, 0, sizeof(fcb));
  fcb.struct_size = (uint32_t)sizeof(fcb);
  fcb.open = host_vfs_open;
  fcb.seek = host_vfs_seek;
  fcb.tell = host_vfs_tell;
  fcb.read = host_vfs_read;
  fcb.close = host_vfs_close;

  omni_ra_host_t h = make_host(&ctx, &fcb);
  omni_ra_t* ra = omni_ra_create(&h);
  ASSERT_TRUE(ra != NULL);
  ASSERT_EQ(omni_ra_set_user_agent(ra, "Omnidroid/3.2.0 omnidroid_ra/1.0.0 rcheevos/12.5.0"), OMNI_RA_OK);

  /* Second completely isolated instance */
  fake_host_ctx_t ctx2;
  memset(&ctx2, 0, sizeof(ctx2));
  ctx2.now_ms = 5000;
  omni_ra_host_t h2 = make_host(&ctx2, NULL);
  omni_ra_t* ra2 = omni_ra_create(&h2);
  ASSERT_TRUE(ra2 != NULL);

  /* Bad credentials login */
  omni_ra_login_with_password(ra, "PlayerOne", "bad", async_cb, &ctx);
  flush_http(ra, &ctx);
  ASSERT_EQ(ctx.last_async_result, OMNI_RA_ERR_INVALID_CREDENTIALS);

  /* Password login -> user info returns token */
  ctx.async_calls = 0;
  omni_ra_login_with_password(ra, "PlayerOne", "secret", async_cb, &ctx);
  flush_http(ra, &ctx);
  ASSERT_EQ(ctx.last_async_result, OMNI_RA_OK);
  ASSERT_STR_EQ(ctx.last_user_agent, "Omnidroid/3.2.0 omnidroid_ra/1.0.0 rcheevos/12.5.0");

  omni_ra_user_info_t uinfo;
  memset(&uinfo, 0, sizeof(uinfo));
  uinfo.struct_size = (uint32_t)sizeof(uinfo);
  ASSERT_EQ(omni_ra_get_user_info(ra, &uinfo), OMNI_RA_OK);
  ASSERT_STR_EQ(uinfo.username, "PlayerOne");
  ASSERT_STR_EQ(uinfo.token, "Tok12345");
  ASSERT_EQ(uinfo.score, 500);

  /* Isolate check: ra2 must remain logged out */
  omni_ra_user_info_t uinfo2;
  memset(&uinfo2, 0, sizeof(uinfo2));
  uinfo2.struct_size = (uint32_t)sizeof(uinfo2);
  ASSERT_EQ(omni_ra_get_user_info(ra2, &uinfo2), OMNI_RA_ERR_LOGIN_REQUIRED);

  /* Logout then re-login by token */
  omni_ra_logout(ra);
  ASSERT_EQ(omni_ra_get_user_info(ra, &uinfo), OMNI_RA_ERR_LOGIN_REQUIRED);
  omni_ra_login_with_token(ra, "PlayerOne", "Tok12345", async_cb, &ctx);
  flush_http(ra, &ctx);
  ASSERT_EQ(ctx.last_async_result, OMNI_RA_OK);

  /* Register Atari 2600 system RAM via omni_ra_set_memory_map */
  omni_ra_core_memory_region_t reg;
  memset(&reg, 0, sizeof(reg));
  reg.struct_size = (uint32_t)sizeof(reg);
  reg.memory_type = RETRO_MEMORY_SYSTEM_RAM;
  reg.data = ctx.ram;
  reg.size = 128;

  omni_ra_memory_map_t mmap;
  memset(&mmap, 0, sizeof(mmap));
  mmap.struct_size = (uint32_t)sizeof(mmap);
  mmap.console_id = 25; /* RC_CONSOLE_ATARI_2600 */
  mmap.num_core_regions = 1;
  mmap.core_regions = &reg;
  ASSERT_EQ(omni_ra_set_memory_map(ra, &mmap), OMNI_RA_OK);

  char dump_buf[512];
  uint32_t dump_len = 0;
  ASSERT_EQ(omni_ra_dump_memory_regions(ra, dump_buf, sizeof(dump_buf), &dump_len), OMNI_RA_OK);
  ASSERT_TRUE(strstr(dump_buf, "console=25") != NULL);

  /* Softcore initially so serialize_progress is valid, identify & load via VFS */
  omni_ra_set_hardcore(ra, 0);
  ASSERT_EQ(omni_ra_get_hardcore(ra), 0);
  omni_ra_begin_identify_and_load_game(ra, 25, "/roms/fixture.a26", NULL, 0, async_cb, &ctx);
  flush_http(ra, &ctx);
  ASSERT_EQ(ctx.last_async_result, OMNI_RA_OK);
  ASSERT_EQ(omni_ra_is_game_loaded(ra), 1);

  omni_ra_game_info_t ginfo;
  memset(&ginfo, 0, sizeof(ginfo));
  ginfo.struct_size = (uint32_t)sizeof(ginfo);
  ASSERT_EQ(omni_ra_get_game_info(ra, &ginfo), OMNI_RA_OK);
  ASSERT_EQ(ginfo.id, 1234);
  ASSERT_STR_EQ(ginfo.title, "Fixture Quest");

  omni_ra_user_game_summary_t summary;
  memset(&summary, 0, sizeof(summary));
  summary.struct_size = (uint32_t)sizeof(summary);
  ASSERT_EQ(omni_ra_get_user_game_summary(ra, &summary), OMNI_RA_OK);
  ASSERT_EQ(summary.num_core_achievements, 1);

  omni_ra_achievement_list_t* alist = omni_ra_get_achievement_list(
      ra, OMNI_RA_ACHIEVEMENT_CATEGORY_CORE, OMNI_RA_ACHIEVEMENT_GROUPING_PROGRESS);
  ASSERT_TRUE(alist != NULL);
  ASSERT_TRUE(alist->num_buckets >= 1);
  omni_ra_free_achievement_list(alist);

  omni_ra_leaderboard_list_t* lblist = omni_ra_get_leaderboard_list(ra, OMNI_RA_LEADERBOARD_GROUPING_NONE);
  ASSERT_TRUE(lblist != NULL);
  ASSERT_TRUE(lblist->num_buckets >= 1);
  omni_ra_free_leaderboard_list(lblist);

  /* Rich presence check */
  ctx.ram[0x0003] = 7;
  ctx.now_ms += 1000;
  omni_ra_do_frame(ra);
  ASSERT_EQ(omni_ra_has_rich_presence(ra), 1);
  char rp[128];
  uint32_t rp_len = 0;
  ASSERT_EQ(omni_ra_get_rich_presence_message(ra, rp, sizeof(rp), &rp_len), OMNI_RA_OK);
  ASSERT_STR_EQ(rp, "Stage 7");

  /* State progress serialize / deserialize */
  uint32_t prog_sz = omni_ra_progress_size(ra);
  ASSERT_TRUE(prog_sz > 0);
  uint8_t* prog_buf = (uint8_t*)malloc(prog_sz);
  ASSERT_EQ(omni_ra_serialize_progress(ra, prog_buf, prog_sz), OMNI_RA_OK);
  ASSERT_EQ(omni_ra_deserialize_progress(ra, prog_buf, prog_sz), OMNI_RA_OK);
  free(prog_buf);

  /* Trigger achievement 5501 while forcing initial + immediate retry to fail cleanly */
  ctx.event_count = 0;
  ctx.fail_award_remaining = 2;
  ctx.ram[0x0001] = 3;
  ctx.now_ms += 17;
  omni_ra_do_frame(ra);
  flush_http(ra, &ctx);
  ctx.now_ms += 17;
  omni_ra_idle(ra);

  int saw_trigger = 0;
  int saw_disconnected = 0;
  for (int i = 0; i < ctx.event_count; ++i) {
    if (ctx.events[i].type == OMNI_RA_EVENT_ACHIEVEMENT_TRIGGERED && ctx.events[i].id == 5501)
      saw_trigger = 1;
    if (ctx.events[i].type == OMNI_RA_EVENT_DISCONNECTED)
      saw_disconnected = 1;
  }
  ASSERT_TRUE(saw_trigger);
  ASSERT_TRUE(saw_disconnected);

  /* Advance clock and call idle so rcheevos retries unlock -> RECONNECTED */
  ctx.event_count = 0;
  ctx.now_ms += 60000;
  omni_ra_idle(ra);
  flush_http(ra, &ctx);
  ctx.now_ms += 17;
  omni_ra_idle(ra);
  int saw_reconnected = 0;
  for (int i = 0; i < ctx.event_count; ++i) {
    if (ctx.events[i].type == OMNI_RA_EVENT_RECONNECTED)
      saw_reconnected = 1;
  }
  ASSERT_TRUE(saw_reconnected);

  /* Switching to hardcore while game is loaded must emit OMNI_RA_EVENT_RESET */
  ctx.event_count = 0;
  omni_ra_set_hardcore(ra, 1);
  int saw_reset = 0;
  for (int i = 0; i < ctx.event_count; ++i) {
    if (ctx.events[i].type == OMNI_RA_EVENT_RESET)
      saw_reset = 1;
  }
  ASSERT_TRUE(saw_reset);
  omni_ra_reset(ra);

  /* Core/setting allow checks via rc_libretro (checks both system_id=0 and system-specific rules) */
  ASSERT_EQ(omni_ra_is_system_allowed("FCEUmm", 7 /* RC_CONSOLE_NINTENDO */), 1);
  ASSERT_EQ(omni_ra_is_system_allowed("Mesen-S", 4 /* RC_CONSOLE_GAMEBOY */), 0);
  ASSERT_EQ(omni_ra_is_setting_allowed("FCEUmm", 7, "fceumm_game_genie", "enabled"), 0);
  ASSERT_EQ(omni_ra_is_setting_allowed("FCEUmm", 7, "fceumm_game_genie", "disabled"), 1);
  ASSERT_EQ(omni_ra_is_setting_allowed("melonDS", 18 /* RC_CONSOLE_NINTENDO_DS */, "melonds_console_mode", "dsi"), 0);

  /* Clear memory map safely and unload */
  ASSERT_EQ(omni_ra_set_memory_map(ra, NULL), OMNI_RA_OK);
  omni_ra_unload_game(ra);
  ASSERT_EQ(omni_ra_is_game_loaded(ra), 0);

  /* Verify safe teardown while an HTTP request is still in-flight */
  omni_ra_login_with_token(ra2, "PlayerOne", "Tok12345", async_cb, &ctx2);
  ASSERT_TRUE(ctx2.queue_count > 0);
  omni_ra_destroy(ra2);
  omni_ra_destroy(ra);
}

int main(void) {
  test_abi_version_and_struct_sizes();
  test_login_load_trigger_retry_hardcore_progress_and_multi_instance();
  printf("All omni_ra C ABI tests passed.\n");
  return 0;
}
