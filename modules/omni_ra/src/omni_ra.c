#include "omni_ra_internal.h"

#include <stdarg.h>
#include <stdio.h>

#define OMNI_RA_STR_HELPER(x) #x
#define OMNI_RA_STR(x) OMNI_RA_STR_HELPER(x)
#define OMNI_RA_RCHEEVOS_VER_FULL \
  OMNI_RA_STR(RCHEEVOS_VERSION_MAJOR) "." OMNI_RA_STR(RCHEEVOS_VERSION_MINOR) "." OMNI_RA_STR(RCHEEVOS_VERSION_PATCH)

#ifndef OMNI_RA_VERSION_STR
  #define OMNI_RA_VERSION_STR "1.0.0"
#endif
#ifndef OMNI_RA_GIT_HASH_STR
  #define OMNI_RA_GIT_HASH_STR "unknown"
#endif

#define OMNI_HAS_FIELD(ptr, type, field) \
  ((ptr) != NULL && (ptr)->struct_size >= (uint32_t)(offsetof(type, field) + sizeof(((type*)0)->field)))

typedef struct omni_ra_file_handle_s {
  omni_ra_t* ra;
  void* host_handle;
} omni_ra_file_handle_t;

typedef struct omni_ra_track_handle_s {
  omni_ra_t* ra;
  void* host_handle;
} omni_ra_track_handle_t;

typedef struct omni_ra_ach_list_wrap_s {
  omni_ra_achievement_list_t api;
  rc_client_achievement_list_t* rc_list;
  omni_ra_achievement_bucket_t* buckets;
  omni_ra_achievement_t* items;
  const omni_ra_achievement_t** ptrs;
} omni_ra_ach_list_wrap_t;

typedef struct omni_ra_lb_list_wrap_s {
  omni_ra_leaderboard_list_t api;
  rc_client_leaderboard_list_t* rc_list;
  omni_ra_leaderboard_bucket_t* buckets;
  omni_ra_leaderboard_t* items;
  const omni_ra_leaderboard_t** ptrs;
} omni_ra_lb_list_wrap_t;

static OMNI_THREAD_LOCAL omni_ra_t* g_tls_active_ra = NULL;
static OMNI_THREAD_LOCAL const omni_ra_memory_map_t* g_tls_mem_init_map = NULL;

static void omni_copy_str(char* dst, size_t cap, const char* src) {
  if (!dst || cap == 0)
    return;
  if (!src) {
    dst[0] = '\0';
    return;
  }
  size_t len = strlen(src);
  if (len >= cap)
    len = cap - 1;
  memcpy(dst, src, len);
  dst[len] = '\0';
}

static char* omni_strdup(const char* src) {
  if (!src)
    return NULL;
  size_t len = strlen(src);
  char* out = (char*)malloc(len + 1);
  if (out)
    memcpy(out, src, len + 1);
  return out;
}

static void omni_write_sized_struct(void* dst, const void* src, uint32_t src_size) {
  uint32_t dst_size = 0;
  if (!dst || !src || src_size < sizeof(uint32_t))
    return;
  memcpy(&dst_size, dst, sizeof(uint32_t));
  if (dst_size < sizeof(uint32_t))
    return;
  uint32_t copy_size = dst_size < src_size ? dst_size : src_size;
  memcpy(dst, src, copy_size);
  memcpy(dst, &copy_size, sizeof(uint32_t));
}

/* =========================================================================
 * rcheevos callback trampolines
 * ========================================================================= */

static uint32_t RC_CCONV omni_ra_read_memory_cb(
    uint32_t address, uint8_t* buffer, uint32_t num_bytes, rc_client_t* client) {
  omni_ra_t* ra = (omni_ra_t*)rc_client_get_userdata(client);
  if (!ra || !buffer || num_bytes == 0)
    return 0;

  uint32_t read_bytes = 0;
  rc_mutex_lock(&ra->mem_lock);
  if (ra->has_mem_map) {
    read_bytes = rc_libretro_memory_read(&ra->mem_regions, address, buffer, num_bytes);
  } else if (ra->host.read_memory) {
    read_bytes = ra->host.read_memory(ra->host.userdata, address, buffer, num_bytes);
  }
  rc_mutex_unlock(&ra->mem_lock);
  return read_bytes;
}

static void RC_CCONV omni_ra_server_call_cb(
    const rc_api_request_t* request, rc_client_server_callback_t callback,
    void* callback_data, rc_client_t* client) {
  omni_ra_t* ra = (omni_ra_t*)rc_client_get_userdata(client);
  if (!ra || !ra->host.http_request || !request) {
    rc_api_server_response_t err;
    memset(&err, 0, sizeof(err));
    err.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
    if (callback)
      callback(&err, callback_data);
    return;
  }

  omni_ra_http_pending_t* node = (omni_ra_http_pending_t*)calloc(1, sizeof(*node));
  if (!node) {
    rc_api_server_response_t err;
    memset(&err, 0, sizeof(err));
    err.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
    if (callback)
      callback(&err, callback_data);
    return;
  }

  rc_mutex_lock(&ra->http_lock);
  if (ra->destroying) {
    rc_mutex_unlock(&ra->http_lock);
    free(node);
    rc_api_server_response_t err;
    memset(&err, 0, sizeof(err));
    err.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
    if (callback)
      callback(&err, callback_data);
    return;
  }
  uint64_t req_id = ++ra->next_request_id;
  if (req_id == 0)
    req_id = ++ra->next_request_id;
  node->request_id = req_id;
  node->callback = callback;
  node->callback_data = callback_data;
  node->next = ra->pending_http;
  ra->pending_http = node;
  char ua_buf[256];
  omni_copy_str(ua_buf, sizeof(ua_buf), ra->user_agent);
  rc_mutex_unlock(&ra->http_lock);

  omni_ra_http_request_t req;
  memset(&req, 0, sizeof(req));
  req.struct_size = (uint32_t)sizeof(req);
  req.url = request->url;
  req.post_data = request->post_data;
  req.content_type = request->content_type;
  req.user_agent = ua_buf[0] ? ua_buf : NULL;

  ra->host.http_request(ra->host.userdata, req_id, &req);
}

static rc_clock_t RC_CCONV omni_ra_time_ms_cb(const rc_client_t* client) {
  const omni_ra_t* ra = (const omni_ra_t*)rc_client_get_userdata(client);
  if (ra && ra->host.get_time_ms)
    return (rc_clock_t)ra->host.get_time_ms(ra->host.userdata);
  return 0;
}

static void omni_ra_emit_log(const omni_ra_t* ra, int32_t level, const char* msg) {
  if (ra && ra->host.log_message && msg && level <= ra->log_level)
    ra->host.log_message(ra->host.userdata, level, msg);
}

static void RC_CCONV omni_ra_log_error_cb(const char* message, const rc_client_t* client) {
  omni_ra_emit_log((const omni_ra_t*)rc_client_get_userdata(client), OMNI_RA_LOG_ERROR, message);
}

static void RC_CCONV omni_ra_log_warn_cb(const char* message, const rc_client_t* client) {
  omni_ra_emit_log((const omni_ra_t*)rc_client_get_userdata(client), OMNI_RA_LOG_WARN, message);
}

static void RC_CCONV omni_ra_log_info_cb(const char* message, const rc_client_t* client) {
  omni_ra_emit_log((const omni_ra_t*)rc_client_get_userdata(client), OMNI_RA_LOG_INFO, message);
}

static void RC_CCONV omni_ra_log_verbose_cb(const char* message, const rc_client_t* client) {
  omni_ra_emit_log((const omni_ra_t*)rc_client_get_userdata(client), OMNI_RA_LOG_VERBOSE, message);
}

static void omni_fill_achievement(omni_ra_achievement_t* dst, const rc_client_achievement_t* src) {
  memset(dst, 0, sizeof(*dst));
  dst->struct_size = (uint32_t)sizeof(*dst);
  dst->id = src->id;
  dst->points = src->points;
  dst->unlock_time = (int64_t)src->unlock_time;
  dst->measured_percent = src->measured_percent;
  dst->rarity = src->rarity;
  dst->rarity_hardcore = src->rarity_hardcore;
  dst->state = src->state;
  dst->category = src->category;
  dst->bucket = src->bucket;
  dst->unlocked = src->unlocked;
  dst->type = src->type;
  dst->title = src->title;
  dst->description = src->description;
  dst->badge_name = src->badge_name;
  dst->measured_progress = src->measured_progress;
  dst->badge_url = src->badge_url;
  dst->badge_locked_url = src->badge_locked_url;
}

static void omni_fill_leaderboard(omni_ra_leaderboard_t* dst, const rc_client_leaderboard_t* src) {
  memset(dst, 0, sizeof(*dst));
  dst->struct_size = (uint32_t)sizeof(*dst);
  dst->id = src->id;
  dst->state = src->state;
  dst->format = src->format;
  dst->lower_is_better = src->lower_is_better;
  dst->title = src->title;
  dst->description = src->description;
  dst->tracker_value = src->tracker_value;
}

static void RC_CCONV omni_ra_event_cb(const rc_client_event_t* event, rc_client_t* client) {
  omni_ra_t* ra = (omni_ra_t*)rc_client_get_userdata(client);
  if (!ra || !ra->host.on_event || !event)
    return;

  omni_ra_event_t out_ev;
  memset(&out_ev, 0, sizeof(out_ev));
  out_ev.struct_size = (uint32_t)sizeof(out_ev);
  out_ev.type = event->type;

  omni_ra_achievement_t ach;
  if (event->achievement) {
    omni_fill_achievement(&ach, event->achievement);
    out_ev.achievement = &ach;
  }

  omni_ra_leaderboard_t lb;
  if (event->leaderboard) {
    omni_fill_leaderboard(&lb, event->leaderboard);
    out_ev.leaderboard = &lb;
  }

  omni_ra_leaderboard_tracker_t tracker;
  if (event->leaderboard_tracker) {
    memset(&tracker, 0, sizeof(tracker));
    tracker.struct_size = (uint32_t)sizeof(tracker);
    tracker.id = event->leaderboard_tracker->id;
    tracker.display = event->leaderboard_tracker->display;
    out_ev.leaderboard_tracker = &tracker;
  }

  omni_ra_leaderboard_scoreboard_t scoreboard;
  omni_ra_leaderboard_scoreboard_entry_t stack_entries[16];
  omni_ra_leaderboard_scoreboard_entry_t* heap_entries = NULL;
  if (event->leaderboard_scoreboard) {
    const rc_client_leaderboard_scoreboard_t* s = event->leaderboard_scoreboard;
    memset(&scoreboard, 0, sizeof(scoreboard));
    scoreboard.struct_size = (uint32_t)sizeof(scoreboard);
    scoreboard.leaderboard_id = s->leaderboard_id;
    scoreboard.new_rank = s->new_rank;
    scoreboard.num_entries = s->num_entries;
    scoreboard.num_top_entries = s->num_top_entries;
    scoreboard.submitted_score = s->submitted_score;
    scoreboard.best_score = s->best_score;

    if (s->num_top_entries > 0 && s->top_entries) {
      omni_ra_leaderboard_scoreboard_entry_t* arr = stack_entries;
      if (s->num_top_entries > 16) {
        heap_entries = (omni_ra_leaderboard_scoreboard_entry_t*)calloc(
            s->num_top_entries, sizeof(omni_ra_leaderboard_scoreboard_entry_t));
        arr = heap_entries;
      }
      if (arr) {
        for (uint32_t i = 0; i < s->num_top_entries; ++i) {
          arr[i].struct_size = (uint32_t)sizeof(arr[i]);
          arr[i].rank = s->top_entries[i].rank;
          arr[i].username = s->top_entries[i].username;
          arr[i].score = s->top_entries[i].score;
        }
        scoreboard.top_entries = arr;
      } else {
        scoreboard.num_top_entries = 0;
      }
    }
    out_ev.leaderboard_scoreboard = &scoreboard;
  }

  omni_ra_server_error_t srv_err;
  if (event->server_error) {
    memset(&srv_err, 0, sizeof(srv_err));
    srv_err.struct_size = (uint32_t)sizeof(srv_err);
    srv_err.result = event->server_error->result;
    srv_err.related_id = event->server_error->related_id;
    srv_err.error_message = event->server_error->error_message;
    srv_err.api = event->server_error->api;
    out_ev.server_error = &srv_err;
  }

  omni_ra_subset_t subset;
  if (event->subset) {
    memset(&subset, 0, sizeof(subset));
    subset.struct_size = (uint32_t)sizeof(subset);
    subset.id = event->subset->id;
    subset.num_achievements = event->subset->num_achievements;
    subset.num_leaderboards = event->subset->num_leaderboards;
    subset.title = event->subset->title;
    subset.badge_name = event->subset->badge_name;
    subset.badge_url = event->subset->badge_url;
    out_ev.subset = &subset;
  }

  ra->host.on_event(ra->host.userdata, &out_ev);
  if (heap_entries)
    free(heap_entries);
}

/* =========================================================================
 * rhash FileReader & CDReader trampolines (multi-instance safe)
 * ========================================================================= */

static void* RC_CCONV omni_ra_file_open(const char* path_utf8) {
  omni_ra_t* ra = g_tls_active_ra;
  if (!ra || !ra->has_file_cbs || !ra->file_cbs.open)
    return NULL;
  void* host_handle = ra->file_cbs.open(ra->host.userdata, path_utf8);
  if (!host_handle)
    return NULL;
  omni_ra_file_handle_t* wrap = (omni_ra_file_handle_t*)malloc(sizeof(*wrap));
  if (!wrap) {
    if (ra->file_cbs.close)
      ra->file_cbs.close(ra->host.userdata, host_handle);
    return NULL;
  }
  wrap->ra = ra;
  wrap->host_handle = host_handle;
  return wrap;
}

static void RC_CCONV omni_ra_file_seek(void* file_handle, int64_t offset, int origin) {
  omni_ra_file_handle_t* wrap = (omni_ra_file_handle_t*)file_handle;
  if (wrap && wrap->ra->file_cbs.seek)
    wrap->ra->file_cbs.seek(wrap->ra->host.userdata, wrap->host_handle, offset, (int32_t)origin);
}

static int64_t RC_CCONV omni_ra_file_tell(void* file_handle) {
  omni_ra_file_handle_t* wrap = (omni_ra_file_handle_t*)file_handle;
  if (wrap && wrap->ra->file_cbs.tell)
    return wrap->ra->file_cbs.tell(wrap->ra->host.userdata, wrap->host_handle);
  return -1;
}

static size_t RC_CCONV omni_ra_file_read(void* file_handle, void* buffer, size_t requested_bytes) {
  omni_ra_file_handle_t* wrap = (omni_ra_file_handle_t*)file_handle;
  if (!wrap || !wrap->ra->file_cbs.read)
    return 0;
  uint32_t chunk = requested_bytes > 0xFFFFFFFFu ? 0xFFFFFFFFu : (uint32_t)requested_bytes;
  return (size_t)wrap->ra->file_cbs.read(wrap->ra->host.userdata, wrap->host_handle, buffer, chunk);
}

static void RC_CCONV omni_ra_file_close(void* file_handle) {
  omni_ra_file_handle_t* wrap = (omni_ra_file_handle_t*)file_handle;
  if (!wrap)
    return;
  if (wrap->ra->file_cbs.close)
    wrap->ra->file_cbs.close(wrap->ra->host.userdata, wrap->host_handle);
  free(wrap);
}

static void* RC_CCONV omni_ra_cd_open_track(const char* path, uint32_t track) {
  omni_ra_t* ra = g_tls_active_ra;
  if (!ra || !ra->has_cdreader_cbs || !ra->cdreader_cbs.open_track)
    return NULL;
  void* host_handle = ra->cdreader_cbs.open_track(ra->host.userdata, path, track);
  if (!host_handle)
    return NULL;
  omni_ra_track_handle_t* wrap = (omni_ra_track_handle_t*)malloc(sizeof(*wrap));
  if (!wrap) {
    if (ra->cdreader_cbs.close_track)
      ra->cdreader_cbs.close_track(ra->host.userdata, host_handle);
    return NULL;
  }
  wrap->ra = ra;
  wrap->host_handle = host_handle;
  return wrap;
}

static size_t RC_CCONV omni_ra_cd_read_sector(
    void* track_handle, uint32_t sector, void* buffer, size_t requested_bytes) {
  omni_ra_track_handle_t* wrap = (omni_ra_track_handle_t*)track_handle;
  if (!wrap || !wrap->ra->cdreader_cbs.read_sector)
    return 0;
  uint32_t chunk = requested_bytes > 0xFFFFFFFFu ? 0xFFFFFFFFu : (uint32_t)requested_bytes;
  return (size_t)wrap->ra->cdreader_cbs.read_sector(
      wrap->ra->host.userdata, wrap->host_handle, sector, buffer, chunk);
}

static void RC_CCONV omni_ra_cd_close_track(void* track_handle) {
  omni_ra_track_handle_t* wrap = (omni_ra_track_handle_t*)track_handle;
  if (!wrap)
    return;
  if (wrap->ra->cdreader_cbs.close_track)
    wrap->ra->cdreader_cbs.close_track(wrap->ra->host.userdata, wrap->host_handle);
  free(wrap);
}

static uint32_t RC_CCONV omni_ra_cd_first_track_sector(void* track_handle) {
  omni_ra_track_handle_t* wrap = (omni_ra_track_handle_t*)track_handle;
  if (wrap && wrap->ra->cdreader_cbs.first_track_sector)
    return wrap->ra->cdreader_cbs.first_track_sector(wrap->ra->host.userdata, wrap->host_handle);
  return 0;
}

static void RC_CCONV omni_ra_get_core_mem_info_tls(uint32_t id, rc_libretro_core_memory_info_t* info) {
  if (!info)
    return;
  info->data = NULL;
  info->size = 0;
  const omni_ra_memory_map_t* map = g_tls_mem_init_map;
  if (!map || !OMNI_HAS_FIELD(map, omni_ra_memory_map_t, core_regions) || !map->core_regions)
    return;
  for (uint32_t i = 0; i < map->num_core_regions; ++i) {
    const omni_ra_core_memory_region_t* reg = &map->core_regions[i];
    if (reg->struct_size < (uint32_t)sizeof(omni_ra_core_memory_region_t))
      continue;
    if (reg->memory_type == id && reg->data != NULL && reg->size > 0) {
      info->data = (uint8_t*)reg->data;
      info->size = (size_t)reg->size;
      return;
    }
  }
}

static void RC_CCONV omni_ra_libretro_verbose_tls(const char* msg) {
  omni_ra_emit_log(g_tls_active_ra, OMNI_RA_LOG_INFO, msg);
}

/* =========================================================================
 * Async wrapper
 * ========================================================================= */

static void RC_CCONV omni_ra_async_trampoline(
    int result, const char* error_message, rc_client_t* client, void* userdata) {
  (void)client;
  omni_ra_async_req_t* req = (omni_ra_async_req_t*)userdata;
  if (!req)
    return;
  omni_ra_t* ra = req->ra;
  omni_ra_async_cb_t cb = req->cb;
  void* cb_userdata = req->cb_userdata;

  rc_mutex_lock(&ra->http_lock);
  req->completed = 1;
  int should_free = req->caller_returned;
  rc_mutex_unlock(&ra->http_lock);

  if (cb)
    cb(cb_userdata, (int32_t)result, error_message);
  if (should_free)
    free(req);
}

typedef enum omni_async_kind_e {
  OMNI_ASYNC_LOGIN_PW = 0,
  OMNI_ASYNC_LOGIN_TOKEN = 1,
  OMNI_ASYNC_IDENTIFY_LOAD = 2,
  OMNI_ASYNC_LOAD_HASH = 3,
  OMNI_ASYNC_CHANGE_MEDIA = 4
} omni_async_kind_t;

static omni_ra_async_t* omni_ra_dispatch_async(
    omni_ra_t* ra, omni_async_kind_t kind,
    const char* s1, const char* s2,
    uint32_t console_id, const uint8_t* data, uint64_t data_size,
    omni_ra_async_cb_t cb, void* cb_userdata) {
  if (!ra || !ra->client) {
    if (cb)
      cb(cb_userdata, OMNI_RA_ERR_INVALID_STATE, "omni_ra instance is null");
    return NULL;
  }

  omni_ra_async_req_t* req = (omni_ra_async_req_t*)calloc(1, sizeof(*req));
  if (!req) {
    if (cb)
      cb(cb_userdata, OMNI_RA_ERR_OUT_OF_MEMORY, "out of memory");
    return NULL;
  }
  req->ra = ra;
  req->cb = cb;
  req->cb_userdata = cb_userdata;
  req->rc_handle = NULL;
  req->completed = 0;
  req->caller_returned = 0;

  omni_ra_t* prev_tls = g_tls_active_ra;
  g_tls_active_ra = ra;

  rc_client_async_handle_t* handle = NULL;
  switch (kind) {
    case OMNI_ASYNC_LOGIN_PW:
      handle = rc_client_begin_login_with_password(ra->client, s1, s2, omni_ra_async_trampoline, req);
      break;
    case OMNI_ASYNC_LOGIN_TOKEN:
      handle = rc_client_begin_login_with_token(ra->client, s1, s2, omni_ra_async_trampoline, req);
      break;
    case OMNI_ASYNC_IDENTIFY_LOAD:
      handle = rc_client_begin_identify_and_load_game(
          ra->client, console_id, s1, data, (size_t)data_size, omni_ra_async_trampoline, req);
      break;
    case OMNI_ASYNC_LOAD_HASH:
      handle = rc_client_begin_load_game(ra->client, s1, omni_ra_async_trampoline, req);
      break;
    case OMNI_ASYNC_CHANGE_MEDIA:
      handle = rc_client_begin_identify_and_change_media(
          ra->client, s1, data, (size_t)data_size, omni_ra_async_trampoline, req);
      break;
  }

  g_tls_active_ra = prev_tls;

  rc_mutex_lock(&ra->http_lock);
  req->rc_handle = handle;
  int already_done = req->completed;
  req->caller_returned = 1;
  rc_mutex_unlock(&ra->http_lock);

  if (already_done || handle == NULL) {
    free(req);
    return NULL;
  }

  return (omni_ra_async_t*)req;
}

/* =========================================================================
 * Exported API
 * ========================================================================= */

uint32_t omni_ra_abi_version(void) {
  return OMNI_RA_MAKE_ABI_VERSION(OMNI_RA_ABI_VERSION_MAJOR, OMNI_RA_ABI_VERSION_MINOR);
}

int32_t omni_ra_get_build_info(omni_ra_build_info_t* out_info) {
  if (!out_info || out_info->struct_size < sizeof(uint32_t))
    return OMNI_RA_ERR_INVALID_ARGUMENT;

  omni_ra_build_info_t tmp;
  memset(&tmp, 0, sizeof(tmp));
  tmp.struct_size = (uint32_t)sizeof(tmp);
  tmp.abi_major = OMNI_RA_ABI_VERSION_MAJOR;
  tmp.abi_minor = OMNI_RA_ABI_VERSION_MINOR;
  tmp.module_version = OMNI_RA_VERSION_STR;
  tmp.rcheevos_version = OMNI_RA_RCHEEVOS_VER_FULL;
  tmp.git_hash = OMNI_RA_GIT_HASH_STR;

  omni_write_sized_struct(out_info, &tmp, (uint32_t)sizeof(tmp));
  return OMNI_RA_OK;
}

omni_ra_t* omni_ra_create(const omni_ra_host_t* host) {
  if (!OMNI_HAS_FIELD(host, omni_ra_host_t, on_event))
    return NULL;
  if (OMNI_RA_ABI_MAJOR(host->host_abi_version) != OMNI_RA_ABI_VERSION_MAJOR)
    return NULL;
  if (!host->get_time_ms || !host->http_request || !host->on_event)
    return NULL;

  omni_ra_t* ra = (omni_ra_t*)calloc(1, sizeof(*ra));
  if (!ra)
    return NULL;

  ra->host_abi_version = host->host_abi_version;
  uint32_t copy_host_sz = host->struct_size < sizeof(ra->host)
      ? host->struct_size : (uint32_t)sizeof(ra->host);
  memcpy(&ra->host, host, copy_host_sz);
  ra->host.struct_size = copy_host_sz;
  ra->log_level = OMNI_RA_LOG_WARN;

  if (OMNI_HAS_FIELD(host, omni_ra_host_t, file_callbacks) && host->file_callbacks &&
      host->file_callbacks->struct_size >= sizeof(uint32_t)) {
    uint32_t fsz = host->file_callbacks->struct_size < sizeof(ra->file_cbs)
        ? host->file_callbacks->struct_size : (uint32_t)sizeof(ra->file_cbs);
    memcpy(&ra->file_cbs, host->file_callbacks, fsz);
    ra->file_cbs.struct_size = fsz;
    ra->has_file_cbs = (ra->file_cbs.open != NULL);
  }

  if (OMNI_HAS_FIELD(host, omni_ra_host_t, cdreader_callbacks) && host->cdreader_callbacks &&
      host->cdreader_callbacks->struct_size >= sizeof(uint32_t)) {
    uint32_t csz = host->cdreader_callbacks->struct_size < sizeof(ra->cdreader_cbs)
        ? host->cdreader_callbacks->struct_size : (uint32_t)sizeof(ra->cdreader_cbs);
    memcpy(&ra->cdreader_cbs, host->cdreader_callbacks, csz);
    ra->cdreader_cbs.struct_size = csz;
    ra->has_cdreader_cbs = (ra->cdreader_cbs.open_track != NULL);
  }

  rc_mutex_init(&ra->http_lock);
  rc_mutex_init(&ra->mem_lock);

  ra->client = rc_client_create(omni_ra_read_memory_cb, omni_ra_server_call_cb);
  if (!ra->client) {
    rc_mutex_destroy(&ra->mem_lock);
    rc_mutex_destroy(&ra->http_lock);
    free(ra);
    return NULL;
  }

  rc_client_set_userdata(ra->client, ra);
  rc_client_set_event_handler(ra->client, omni_ra_event_cb);
  rc_client_set_get_time_millisecs_function(ra->client, omni_ra_time_ms_cb);

  if (ra->has_file_cbs || ra->has_cdreader_cbs) {
    rc_hash_callbacks_t hcbs;
    memset(&hcbs, 0, sizeof(hcbs));
    if (ra->has_file_cbs) {
      hcbs.filereader.open = omni_ra_file_open;
      hcbs.filereader.seek = omni_ra_file_seek;
      hcbs.filereader.tell = omni_ra_file_tell;
      hcbs.filereader.read = omni_ra_file_read;
      hcbs.filereader.close = omni_ra_file_close;
    }
    if (ra->has_cdreader_cbs) {
      hcbs.cdreader.open_track = omni_ra_cd_open_track;
      hcbs.cdreader.read_sector = omni_ra_cd_read_sector;
      hcbs.cdreader.close_track = omni_ra_cd_close_track;
      hcbs.cdreader.first_track_sector = omni_ra_cd_first_track_sector;
    }
    rc_client_set_hash_callbacks(ra->client, &hcbs);
  }

  omni_ra_set_log_level(ra, ra->log_level);
  return ra;
}

void omni_ra_destroy(omni_ra_t* ra) {
  if (!ra)
    return;

  rc_mutex_lock(&ra->http_lock);
  ra->destroying = 1;
  omni_ra_http_pending_t* drained = ra->pending_http;
  ra->pending_http = NULL;
  rc_mutex_unlock(&ra->http_lock);

  rc_api_server_response_t abort_resp;
  memset(&abort_resp, 0, sizeof(abort_resp));
  abort_resp.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
  while (drained) {
    omni_ra_http_pending_t* next = drained->next;
    if (drained->callback)
      drained->callback(&abort_resp, drained->callback_data);
    free(drained);
    drained = next;
  }

  if (ra->client)
    rc_client_destroy(ra->client);

  rc_mutex_lock(&ra->mem_lock);
  if (ra->has_mem_map) {
    rc_libretro_memory_destroy(&ra->mem_regions);
    ra->has_mem_map = 0;
  }
  rc_mutex_unlock(&ra->mem_lock);

  rc_mutex_destroy(&ra->mem_lock);
  rc_mutex_destroy(&ra->http_lock);
  free(ra->user_agent);
  free(ra);
}

int32_t omni_ra_set_user_agent(omni_ra_t* ra, const char* user_agent) {
  if (!ra || !user_agent || !user_agent[0])
    return OMNI_RA_ERR_INVALID_ARGUMENT;
  char* copy = omni_strdup(user_agent);
  if (!copy)
    return OMNI_RA_ERR_OUT_OF_MEMORY;
  rc_mutex_lock(&ra->http_lock);
  char* old = ra->user_agent;
  ra->user_agent = copy;
  rc_mutex_unlock(&ra->http_lock);
  free(old);
  return OMNI_RA_OK;
}

int32_t omni_ra_set_host(omni_ra_t* ra, const char* hostname) {
  if (!ra || !ra->client)
    return OMNI_RA_ERR_INVALID_STATE;
  rc_client_set_host(ra->client, hostname);
  return OMNI_RA_OK;
}

void omni_ra_set_log_level(omni_ra_t* ra, int32_t level) {
  if (!ra || !ra->client)
    return;
  ra->log_level = level;
  if (!ra->host.log_message || level <= OMNI_RA_LOG_NONE) {
    rc_client_enable_logging(ra->client, RC_CLIENT_LOG_LEVEL_NONE, NULL);
    return;
  }
  rc_client_message_callback_t cb = omni_ra_log_warn_cb;
  int rc_lvl = RC_CLIENT_LOG_LEVEL_WARN;
  switch (level) {
    case OMNI_RA_LOG_ERROR:   rc_lvl = RC_CLIENT_LOG_LEVEL_ERROR; cb = omni_ra_log_error_cb; break;
    case OMNI_RA_LOG_WARN:    rc_lvl = RC_CLIENT_LOG_LEVEL_WARN; cb = omni_ra_log_warn_cb; break;
    case OMNI_RA_LOG_INFO:    rc_lvl = RC_CLIENT_LOG_LEVEL_INFO; cb = omni_ra_log_info_cb; break;
    default:                  rc_lvl = RC_CLIENT_LOG_LEVEL_VERBOSE; cb = omni_ra_log_verbose_cb; break;
  }
  rc_client_enable_logging(ra->client, rc_lvl, cb);
}

void omni_ra_set_hardcore(omni_ra_t* ra, int32_t enabled) {
  if (ra && ra->client)
    rc_client_set_hardcore_enabled(ra->client, enabled ? 1 : 0);
}

int32_t omni_ra_get_hardcore(const omni_ra_t* ra) {
  if (!ra || !ra->client)
    return 0;
  return rc_client_get_hardcore_enabled(ra->client) ? 1 : 0;
}

void omni_ra_set_encore_mode(omni_ra_t* ra, int32_t enabled) {
  if (ra && ra->client)
    rc_client_set_encore_mode_enabled(ra->client, enabled ? 1 : 0);
}

void omni_ra_set_unofficial_enabled(omni_ra_t* ra, int32_t enabled) {
  if (ra && ra->client)
    rc_client_set_unofficial_enabled(ra->client, enabled ? 1 : 0);
}

void omni_ra_set_spectator_mode(omni_ra_t* ra, int32_t enabled) {
  if (ra && ra->client)
    rc_client_set_spectator_mode_enabled(ra->client, enabled ? 1 : 0);
}

int32_t omni_ra_is_system_allowed(const char* library_name, uint32_t console_id) {
  if (!library_name || !library_name[0])
    return 0;
  return rc_libretro_is_system_allowed(library_name, console_id) ? 1 : 0;
}

int32_t omni_ra_is_setting_allowed(
    const char* library_name, uint32_t console_id,
    const char* setting_key, const char* setting_value) {
  if (!library_name || !library_name[0] || !setting_key || !setting_value)
    return 1;
  const rc_disallowed_setting_t* global_rules =
      rc_libretro_get_disallowed_settings(library_name);
  if (global_rules && !rc_libretro_is_setting_allowed(global_rules, setting_key, setting_value))
    return 0;
  if (console_id != 0) {
    const rc_disallowed_setting_t* sys_rules =
        rc_libretro_get_disallowed_settings_for_system(library_name, console_id);
    if (sys_rules && !rc_libretro_is_setting_allowed(sys_rules, setting_key, setting_value))
      return 0;
  }
  return 1;
}

omni_ra_async_t* omni_ra_login_with_password(
    omni_ra_t* ra, const char* username, const char* password,
    omni_ra_async_cb_t cb, void* cb_userdata) {
  return omni_ra_dispatch_async(ra, OMNI_ASYNC_LOGIN_PW, username, password, 0, NULL, 0, cb, cb_userdata);
}

omni_ra_async_t* omni_ra_login_with_token(
    omni_ra_t* ra, const char* username, const char* token,
    omni_ra_async_cb_t cb, void* cb_userdata) {
  return omni_ra_dispatch_async(ra, OMNI_ASYNC_LOGIN_TOKEN, username, token, 0, NULL, 0, cb, cb_userdata);
}

void omni_ra_logout(omni_ra_t* ra) {
  if (ra && ra->client)
    rc_client_logout(ra->client);
}

int32_t omni_ra_get_user_info(const omni_ra_t* ra, omni_ra_user_info_t* out_user) {
  if (!ra || !ra->client || !out_user || out_user->struct_size < sizeof(uint32_t))
    return OMNI_RA_ERR_INVALID_ARGUMENT;
  const rc_client_user_t* user = rc_client_get_user_info(ra->client);
  if (!user)
    return OMNI_RA_ERR_LOGIN_REQUIRED;

  omni_ra_user_info_t tmp;
  memset(&tmp, 0, sizeof(tmp));
  tmp.struct_size = (uint32_t)sizeof(tmp);
  tmp.score = user->score;
  tmp.score_softcore = user->score_softcore;
  tmp.num_unread_messages = user->num_unread_messages;
  tmp.avatar_last_updated = (int64_t)user->avatar_last_updated;
  omni_copy_str(tmp.username, sizeof(tmp.username), user->username);
  omni_copy_str(tmp.display_name, sizeof(tmp.display_name), user->display_name);
  omni_copy_str(tmp.token, sizeof(tmp.token), user->token);
  omni_copy_str(tmp.avatar_url, sizeof(tmp.avatar_url), user->avatar_url);

  omni_write_sized_struct(out_user, &tmp, (uint32_t)sizeof(tmp));
  return OMNI_RA_OK;
}

void omni_ra_cancel_async(omni_ra_t* ra, omni_ra_async_t* handle) {
  omni_ra_async_req_t* req = (omni_ra_async_req_t*)handle;
  if (!ra || !ra->client || !req || req->ra != ra || !req->rc_handle)
    return;
  rc_client_abort_async(ra->client, req->rc_handle);
}

void omni_ra_http_complete(
    omni_ra_t* ra, uint64_t request_id, const omni_ra_http_response_t* response) {
  if (!ra || request_id == 0)
    return;

  rc_mutex_lock(&ra->http_lock);
  if (ra->destroying) {
    rc_mutex_unlock(&ra->http_lock);
    return;
  }
  omni_ra_http_pending_t** link = &ra->pending_http;
  omni_ra_http_pending_t* found = NULL;
  while (*link) {
    if ((*link)->request_id == request_id) {
      found = *link;
      *link = found->next;
      break;
    }
    link = &(*link)->next;
  }
  rc_mutex_unlock(&ra->http_lock);

  if (!found)
    return;

  rc_api_server_response_t resp;
  memset(&resp, 0, sizeof(resp));
  if (OMNI_HAS_FIELD(response, omni_ra_http_response_t, http_status)) {
    resp.http_status_code = response->http_status;
    if (OMNI_HAS_FIELD(response, omni_ra_http_response_t, body_len)) {
      resp.body = (const char*)response->body;
      resp.body_length = (size_t)response->body_len;
    }
  } else {
    resp.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
  }

  omni_ra_t* prev_tls = g_tls_active_ra;
  g_tls_active_ra = ra;
  if (found->callback)
    found->callback(&resp, found->callback_data);
  g_tls_active_ra = prev_tls;

  free(found);
}

int32_t omni_ra_set_memory_map(omni_ra_t* ra, const omni_ra_memory_map_t* map) {
  if (!ra)
    return OMNI_RA_ERR_INVALID_ARGUMENT;

  rc_mutex_lock(&ra->mem_lock);
  if (ra->has_mem_map) {
    rc_libretro_memory_destroy(&ra->mem_regions);
    ra->has_mem_map = 0;
    ra->mapped_console_id = 0;
  }

  if (!map) {
    rc_mutex_unlock(&ra->mem_lock);
    return OMNI_RA_OK;
  }

  if (!OMNI_HAS_FIELD(map, omni_ra_memory_map_t, console_id)) {
    rc_mutex_unlock(&ra->mem_lock);
    return OMNI_RA_ERR_INVALID_ARGUMENT;
  }

  struct retro_memory_descriptor* retro_descs = NULL;
  struct retro_memory_map retro_mmap;
  const struct retro_memory_map* mmap_ptr = NULL;

  if (OMNI_HAS_FIELD(map, omni_ra_memory_map_t, num_descriptors) &&
      map->num_descriptors > 0 && map->descriptors != NULL) {
    retro_descs = (struct retro_memory_descriptor*)calloc(
        map->num_descriptors, sizeof(struct retro_memory_descriptor));
    if (!retro_descs) {
      rc_mutex_unlock(&ra->mem_lock);
      return OMNI_RA_ERR_OUT_OF_MEMORY;
    }
    for (uint32_t i = 0; i < map->num_descriptors; ++i) {
      const omni_ra_memory_descriptor_t* s = &map->descriptors[i];
      if (!OMNI_HAS_FIELD(s, omni_ra_memory_descriptor_t, len))
        continue;
      retro_descs[i].flags = s->flags;
      retro_descs[i].ptr = s->ptr;
      retro_descs[i].offset = (size_t)s->offset;
      retro_descs[i].start = (size_t)s->start;
      retro_descs[i].select = (size_t)s->select;
      retro_descs[i].disconnect = (size_t)s->disconnect;
      retro_descs[i].len = (size_t)s->len;
      retro_descs[i].addrspace = OMNI_HAS_FIELD(s, omni_ra_memory_descriptor_t, addrspace)
          ? s->addrspace : NULL;
    }
    retro_mmap.descriptors = retro_descs;
    retro_mmap.num_descriptors = map->num_descriptors;
    mmap_ptr = &retro_mmap;
  }

  omni_ra_t* prev_tls_ra = g_tls_active_ra;
  const omni_ra_memory_map_t* prev_tls_map = g_tls_mem_init_map;
  g_tls_active_ra = ra;
  g_tls_mem_init_map = map;
  rc_libretro_init_verbose_message_callback(
      (ra->host.log_message && ra->log_level >= OMNI_RA_LOG_INFO) ? omni_ra_libretro_verbose_tls : NULL);

  int ok = rc_libretro_memory_init(
      &ra->mem_regions, mmap_ptr, omni_ra_get_core_mem_info_tls, map->console_id);

  rc_libretro_init_verbose_message_callback(NULL);
  g_tls_mem_init_map = prev_tls_map;
  g_tls_active_ra = prev_tls_ra;
  free(retro_descs);

  if (ok) {
    ra->has_mem_map = 1;
    ra->mapped_console_id = map->console_id;
    rc_mutex_unlock(&ra->mem_lock);
    return OMNI_RA_OK;
  }

  rc_libretro_memory_destroy(&ra->mem_regions);
  ra->has_mem_map = 0;
  rc_mutex_unlock(&ra->mem_lock);
  return OMNI_RA_ERR_INVALID_STATE;
}

int32_t omni_ra_dump_memory_regions(
    const omni_ra_t* ra, char* buffer, uint32_t buffer_size, uint32_t* out_len) {
  if (!ra)
    return OMNI_RA_ERR_INVALID_ARGUMENT;

  omni_ra_t* mut_ra = (omni_ra_t*)ra;
  rc_mutex_lock(&mut_ra->mem_lock);
  char line[128];
  size_t total = 0;
  size_t pos = 0;

  int hdr_len = snprintf(
      line, sizeof(line), "console=%u mapped=%d count=%u total_bytes=%zu\n",
      ra->mapped_console_id, ra->has_mem_map, ra->mem_regions.count, ra->mem_regions.total_size);
  if (hdr_len > 0) {
    total += (size_t)hdr_len;
    if (buffer && buffer_size > 1) {
      size_t avail = buffer_size - 1 - pos;
      size_t cp = (size_t)hdr_len < avail ? (size_t)hdr_len : avail;
      memcpy(buffer + pos, line, cp);
      pos += cp;
    }
  }

  for (uint32_t i = 0; i < ra->mem_regions.count && i < RC_LIBRETRO_MAX_MEMORY_REGIONS; ++i) {
    int n = snprintf(
        line, sizeof(line), "  [%u] ptr=%p size=0x%zx\n",
        i, (const void*)ra->mem_regions.data[i], ra->mem_regions.size[i]);
    if (n > 0) {
      total += (size_t)n;
      if (buffer && buffer_size > 1 && pos < buffer_size - 1) {
        size_t avail = buffer_size - 1 - pos;
        size_t cp = (size_t)n < avail ? (size_t)n : avail;
        memcpy(buffer + pos, line, cp);
        pos += cp;
      }
    }
  }
  rc_mutex_unlock(&mut_ra->mem_lock);

  if (buffer && buffer_size > 0)
    buffer[pos] = '\0';
  if (out_len)
    *out_len = (uint32_t)total;
  if (buffer && total >= buffer_size)
    return OMNI_RA_ERR_INSUFFICIENT_BUFFER;
  return OMNI_RA_OK;
}

omni_ra_async_t* omni_ra_begin_identify_and_load_game(
    omni_ra_t* ra, uint32_t console_id, const char* file_path_utf8,
    const uint8_t* data, uint64_t data_size,
    omni_ra_async_cb_t cb, void* cb_userdata) {
  if (ra)
    ra->active_console_id = console_id;
  return omni_ra_dispatch_async(
      ra, OMNI_ASYNC_IDENTIFY_LOAD, file_path_utf8, NULL, console_id, data, data_size, cb, cb_userdata);
}

omni_ra_async_t* omni_ra_begin_load_game_hash(
    omni_ra_t* ra, const char* hash, omni_ra_async_cb_t cb, void* cb_userdata) {
  return omni_ra_dispatch_async(ra, OMNI_ASYNC_LOAD_HASH, hash, NULL, 0, NULL, 0, cb, cb_userdata);
}

omni_ra_async_t* omni_ra_begin_identify_and_change_media(
    omni_ra_t* ra, const char* file_path_utf8, const uint8_t* data, uint64_t data_size,
    omni_ra_async_cb_t cb, void* cb_userdata) {
  return omni_ra_dispatch_async(
      ra, OMNI_ASYNC_CHANGE_MEDIA, file_path_utf8, NULL, 0, data, data_size, cb, cb_userdata);
}

void omni_ra_unload_game(omni_ra_t* ra) {
  if (ra && ra->client)
    rc_client_unload_game(ra->client);
}

int32_t omni_ra_is_game_loaded(const omni_ra_t* ra) {
  if (!ra || !ra->client)
    return 0;
  return rc_client_is_game_loaded(ra->client) ? 1 : 0;
}

int32_t omni_ra_get_load_state(const omni_ra_t* ra) {
  if (!ra || !ra->client)
    return OMNI_RA_LOAD_NONE;
  return (int32_t)rc_client_get_load_game_state(ra->client);
}

void omni_ra_do_frame(omni_ra_t* ra) {
  if (ra && ra->client)
    rc_client_do_frame(ra->client);
}

void omni_ra_idle(omni_ra_t* ra) {
  if (ra && ra->client)
    rc_client_idle(ra->client);
}

void omni_ra_reset(omni_ra_t* ra) {
  if (ra && ra->client)
    rc_client_reset(ra->client);
}

int32_t omni_ra_is_processing_required(omni_ra_t* ra) {
  if (!ra || !ra->client)
    return 0;
  return rc_client_is_processing_required(ra->client) ? 1 : 0;
}

int32_t omni_ra_can_pause(omni_ra_t* ra, uint32_t* out_frames_remaining) {
  if (!ra || !ra->client) {
    if (out_frames_remaining)
      *out_frames_remaining = 0;
    return 1;
  }
  uint32_t rem = 0;
  int can = rc_client_can_pause(ra->client, &rem);
  if (out_frames_remaining)
    *out_frames_remaining = rem;
  return can ? 1 : 0;
}

uint32_t omni_ra_progress_size(omni_ra_t* ra) {
  if (!ra || !ra->client)
    return 0;
  size_t sz = rc_client_progress_size(ra->client);
  return sz > 0xFFFFFFFFu ? 0 : (uint32_t)sz;
}

int32_t omni_ra_serialize_progress(omni_ra_t* ra, uint8_t* buffer, uint32_t buffer_size) {
  if (!ra || !ra->client || !buffer)
    return OMNI_RA_ERR_INVALID_ARGUMENT;
  return (int32_t)rc_client_serialize_progress_sized(ra->client, buffer, (size_t)buffer_size);
}

int32_t omni_ra_deserialize_progress(omni_ra_t* ra, const uint8_t* buffer, uint32_t buffer_size) {
  if (!ra || !ra->client || !buffer)
    return OMNI_RA_ERR_INVALID_ARGUMENT;
  return (int32_t)rc_client_deserialize_progress_sized(ra->client, buffer, (size_t)buffer_size);
}

int32_t omni_ra_get_game_info(const omni_ra_t* ra, omni_ra_game_info_t* out_game) {
  if (!ra || !ra->client || !out_game || out_game->struct_size < sizeof(uint32_t))
    return OMNI_RA_ERR_INVALID_ARGUMENT;
  const rc_client_game_t* game = rc_client_get_game_info(ra->client);
  if (!game)
    return OMNI_RA_ERR_NO_GAME_LOADED;

  omni_ra_game_info_t tmp;
  memset(&tmp, 0, sizeof(tmp));
  tmp.struct_size = (uint32_t)sizeof(tmp);
  tmp.id = game->id;
  tmp.console_id = game->console_id;
  omni_copy_str(tmp.title, sizeof(tmp.title), game->title);
  omni_copy_str(tmp.hash, sizeof(tmp.hash), game->hash);
  omni_copy_str(tmp.badge_name, sizeof(tmp.badge_name), game->badge_name);
  omni_copy_str(tmp.badge_url, sizeof(tmp.badge_url), game->badge_url);

  omni_write_sized_struct(out_game, &tmp, (uint32_t)sizeof(tmp));
  return OMNI_RA_OK;
}

int32_t omni_ra_get_user_game_summary(const omni_ra_t* ra, omni_ra_user_game_summary_t* out_summary) {
  if (!ra || !ra->client || !out_summary || out_summary->struct_size < sizeof(uint32_t))
    return OMNI_RA_ERR_INVALID_ARGUMENT;

  rc_client_user_game_summary_t s;
  memset(&s, 0, sizeof(s));
  rc_client_get_user_game_summary(ra->client, &s);

  omni_ra_user_game_summary_t tmp;
  memset(&tmp, 0, sizeof(tmp));
  tmp.struct_size = (uint32_t)sizeof(tmp);
  tmp.num_core_achievements = s.num_core_achievements;
  tmp.num_unofficial_achievements = s.num_unofficial_achievements;
  tmp.num_unlocked_achievements = s.num_unlocked_achievements;
  tmp.num_unsupported_achievements = s.num_unsupported_achievements;
  tmp.points_core = s.points_core;
  tmp.points_unlocked = s.points_unlocked;
  tmp.beaten_time = (int64_t)s.beaten_time;
  tmp.completed_time = (int64_t)s.completed_time;

  omni_write_sized_struct(out_summary, &tmp, (uint32_t)sizeof(tmp));
  return OMNI_RA_OK;
}

int32_t omni_ra_has_rich_presence(omni_ra_t* ra) {
  if (!ra || !ra->client)
    return 0;
  return rc_client_has_rich_presence(ra->client) ? 1 : 0;
}

int32_t omni_ra_get_rich_presence_message(
    omni_ra_t* ra, char* buffer, uint32_t buffer_size, uint32_t* out_len) {
  if (!ra || !ra->client)
    return OMNI_RA_ERR_INVALID_STATE;
  if (!buffer || buffer_size == 0) {
    char scratch[512];
    size_t len = rc_client_get_rich_presence_message(ra->client, scratch, sizeof(scratch));
    if (out_len)
      *out_len = (uint32_t)len;
    return buffer_size == 0 ? OMNI_RA_OK : OMNI_RA_ERR_INSUFFICIENT_BUFFER;
  }
  size_t written = rc_client_get_rich_presence_message(ra->client, buffer, (size_t)buffer_size);
  if (out_len)
    *out_len = (uint32_t)written;
  return OMNI_RA_OK;
}

omni_ra_achievement_list_t* omni_ra_get_achievement_list(
    omni_ra_t* ra, int32_t category, int32_t grouping) {
  if (!ra || !ra->client)
    return NULL;
  rc_client_achievement_list_t* rc_list = rc_client_create_achievement_list(ra->client, category, grouping);
  if (!rc_list)
    return NULL;

  omni_ra_ach_list_wrap_t* wrap = (omni_ra_ach_list_wrap_t*)calloc(1, sizeof(*wrap));
  if (!wrap) {
    rc_client_destroy_achievement_list(rc_list);
    return NULL;
  }
  wrap->rc_list = rc_list;
  wrap->api.struct_size = (uint32_t)sizeof(omni_ra_achievement_list_t);
  wrap->api.num_buckets = rc_list->num_buckets;

  uint32_t total_items = 0;
  for (uint32_t b = 0; b < rc_list->num_buckets; ++b)
    total_items += rc_list->buckets[b].num_achievements;

  if (rc_list->num_buckets > 0) {
    wrap->buckets = (omni_ra_achievement_bucket_t*)calloc(
        rc_list->num_buckets, sizeof(omni_ra_achievement_bucket_t));
    if (!wrap->buckets) {
      omni_ra_free_achievement_list(&wrap->api);
      return NULL;
    }
  }
  if (total_items > 0) {
    wrap->items = (omni_ra_achievement_t*)calloc(total_items, sizeof(omni_ra_achievement_t));
    wrap->ptrs = (const omni_ra_achievement_t**)calloc(total_items, sizeof(omni_ra_achievement_t*));
    if (!wrap->items || !wrap->ptrs) {
      omni_ra_free_achievement_list(&wrap->api);
      return NULL;
    }
  }

  uint32_t idx = 0;
  for (uint32_t b = 0; b < rc_list->num_buckets; ++b) {
    const rc_client_achievement_bucket_t* src_b = &rc_list->buckets[b];
    omni_ra_achievement_bucket_t* dst_b = &wrap->buckets[b];
    dst_b->struct_size = (uint32_t)sizeof(*dst_b);
    dst_b->subset_id = src_b->subset_id;
    dst_b->bucket_type = src_b->bucket_type;
    dst_b->num_achievements = src_b->num_achievements;
    dst_b->label = src_b->label;
    dst_b->achievements = src_b->num_achievements > 0 ? &wrap->ptrs[idx] : NULL;
    for (uint32_t a = 0; a < src_b->num_achievements; ++a) {
      omni_fill_achievement(&wrap->items[idx], src_b->achievements[a]);
      wrap->ptrs[idx] = &wrap->items[idx];
      ++idx;
    }
  }
  wrap->api.buckets = wrap->buckets;
  return &wrap->api;
}

void omni_ra_free_achievement_list(omni_ra_achievement_list_t* list) {
  if (!list)
    return;
  omni_ra_ach_list_wrap_t* wrap = (omni_ra_ach_list_wrap_t*)list;
  if (wrap->rc_list)
    rc_client_destroy_achievement_list(wrap->rc_list);
  free(wrap->ptrs);
  free(wrap->items);
  free(wrap->buckets);
  free(wrap);
}

omni_ra_leaderboard_list_t* omni_ra_get_leaderboard_list(omni_ra_t* ra, int32_t grouping) {
  if (!ra || !ra->client)
    return NULL;
  rc_client_leaderboard_list_t* rc_list = rc_client_create_leaderboard_list(ra->client, grouping);
  if (!rc_list)
    return NULL;

  omni_ra_lb_list_wrap_t* wrap = (omni_ra_lb_list_wrap_t*)calloc(1, sizeof(*wrap));
  if (!wrap) {
    rc_client_destroy_leaderboard_list(rc_list);
    return NULL;
  }
  wrap->rc_list = rc_list;
  wrap->api.struct_size = (uint32_t)sizeof(omni_ra_leaderboard_list_t);
  wrap->api.num_buckets = rc_list->num_buckets;

  uint32_t total_items = 0;
  for (uint32_t b = 0; b < rc_list->num_buckets; ++b)
    total_items += rc_list->buckets[b].num_leaderboards;

  if (rc_list->num_buckets > 0) {
    wrap->buckets = (omni_ra_leaderboard_bucket_t*)calloc(
        rc_list->num_buckets, sizeof(omni_ra_leaderboard_bucket_t));
    if (!wrap->buckets) {
      omni_ra_free_leaderboard_list(&wrap->api);
      return NULL;
    }
  }
  if (total_items > 0) {
    wrap->items = (omni_ra_leaderboard_t*)calloc(total_items, sizeof(omni_ra_leaderboard_t));
    wrap->ptrs = (const omni_ra_leaderboard_t**)calloc(total_items, sizeof(omni_ra_leaderboard_t*));
    if (!wrap->items || !wrap->ptrs) {
      omni_ra_free_leaderboard_list(&wrap->api);
      return NULL;
    }
  }

  uint32_t idx = 0;
  for (uint32_t b = 0; b < rc_list->num_buckets; ++b) {
    const rc_client_leaderboard_bucket_t* src_b = &rc_list->buckets[b];
    omni_ra_leaderboard_bucket_t* dst_b = &wrap->buckets[b];
    dst_b->struct_size = (uint32_t)sizeof(*dst_b);
    dst_b->subset_id = src_b->subset_id;
    dst_b->bucket_type = src_b->bucket_type;
    dst_b->num_leaderboards = src_b->num_leaderboards;
    dst_b->label = src_b->label;
    dst_b->leaderboards = src_b->num_leaderboards > 0 ? &wrap->ptrs[idx] : NULL;
    for (uint32_t i = 0; i < src_b->num_leaderboards; ++i) {
      omni_fill_leaderboard(&wrap->items[idx], src_b->leaderboards[i]);
      wrap->ptrs[idx] = &wrap->items[idx];
      ++idx;
    }
  }
  wrap->api.buckets = wrap->buckets;
  return &wrap->api;
}

void omni_ra_free_leaderboard_list(omni_ra_leaderboard_list_t* list) {
  if (!list)
    return;
  omni_ra_lb_list_wrap_t* wrap = (omni_ra_lb_list_wrap_t*)list;
  if (wrap->rc_list)
    rc_client_destroy_leaderboard_list(wrap->rc_list);
  free(wrap->ptrs);
  free(wrap->items);
  free(wrap->buckets);
  free(wrap);
}
