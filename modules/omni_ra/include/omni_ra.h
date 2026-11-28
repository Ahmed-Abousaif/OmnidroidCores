#ifndef OMNI_RA_H
#define OMNI_RA_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
  #if defined(OMNI_RA_BUILDING_DLL)
    #define OMNI_RA_EXPORT __declspec(dllexport)
  #else
    #define OMNI_RA_EXPORT
  #endif
#else
  #define OMNI_RA_EXPORT __attribute__((visibility("default")))
#endif

/* =========================================================================
 * ABI Versioning
 * Major: breaking layout/signature changes (app rejects unsupported major).
 * Minor: additive fields at struct tails or new omni_ra_* symbols.
 * ========================================================================= */
#define OMNI_RA_ABI_VERSION_MAJOR 1u
#define OMNI_RA_ABI_VERSION_MINOR 0u
#define OMNI_RA_MAKE_ABI_VERSION(major, minor) ((((uint32_t)(major)) << 16) | ((uint32_t)(minor) & 0xFFFFu))
#define OMNI_RA_ABI_MAJOR(ver) (((uint32_t)(ver)) >> 16)
#define OMNI_RA_ABI_MINOR(ver) (((uint32_t)(ver)) & 0xFFFFu)

/* Return codes mirror rc_error.h values directly where applicable */
typedef enum omni_ra_result_e {
  OMNI_RA_OK = 0,
  OMNI_RA_ERR_OUT_OF_MEMORY = -19,
  OMNI_RA_ERR_INVALID_STATE = -25,
  OMNI_RA_ERR_INVALID_JSON = -26,
  OMNI_RA_ERR_API_FAILURE = -27,
  OMNI_RA_ERR_LOGIN_REQUIRED = -28,
  OMNI_RA_ERR_NO_GAME_LOADED = -29,
  OMNI_RA_ERR_HARDCORE_DISABLED = -30,
  OMNI_RA_ERR_ABORTED = -31,
  OMNI_RA_ERR_NO_RESPONSE = -32,
  OMNI_RA_ERR_ACCESS_DENIED = -33,
  OMNI_RA_ERR_INVALID_CREDENTIALS = -34,
  OMNI_RA_ERR_EXPIRED_TOKEN = -35,
  OMNI_RA_ERR_INSUFFICIENT_BUFFER = -36,
  OMNI_RA_ERR_NOT_FOUND = -39,
  OMNI_RA_ERR_INVALID_VALUE = -40,
  OMNI_RA_ERR_UNSUPPORTED_ABI = -100,
  OMNI_RA_ERR_INVALID_ARGUMENT = -101
} omni_ra_result_t;

typedef enum omni_ra_log_level_e {
  OMNI_RA_LOG_NONE = 0,
  OMNI_RA_LOG_ERROR = 1,
  OMNI_RA_LOG_WARN = 2,
  OMNI_RA_LOG_INFO = 3,
  OMNI_RA_LOG_VERBOSE = 4
} omni_ra_log_level_t;

typedef enum omni_ra_http_status_e {
  OMNI_RA_HTTP_CLIENT_ERROR = -1,            /* Non-retryable transport error */
  OMNI_RA_HTTP_RETRYABLE_CLIENT_ERROR = -2   /* Retryable network/timeout error */
} omni_ra_http_status_t;

typedef enum omni_ra_load_state_e {
  OMNI_RA_LOAD_NONE = 0,
  OMNI_RA_LOAD_AWAIT_LOGIN = 1,
  OMNI_RA_LOAD_IDENTIFYING_GAME = 2,
  OMNI_RA_LOAD_FETCHING_GAME_DATA = 3,
  OMNI_RA_LOAD_STARTING_SESSION = 4,
  OMNI_RA_LOAD_DONE = 5,
  OMNI_RA_LOAD_ABORTED = 6
} omni_ra_load_state_t;

typedef enum omni_ra_event_type_e {
  OMNI_RA_EVENT_NONE = 0,
  OMNI_RA_EVENT_ACHIEVEMENT_TRIGGERED = 1,
  OMNI_RA_EVENT_LEADERBOARD_STARTED = 2,
  OMNI_RA_EVENT_LEADERBOARD_FAILED = 3,
  OMNI_RA_EVENT_LEADERBOARD_SUBMITTED = 4,
  OMNI_RA_EVENT_CHALLENGE_INDICATOR_SHOW = 5,
  OMNI_RA_EVENT_CHALLENGE_INDICATOR_HIDE = 6,
  OMNI_RA_EVENT_PROGRESS_INDICATOR_SHOW = 7,
  OMNI_RA_EVENT_PROGRESS_INDICATOR_HIDE = 8,
  OMNI_RA_EVENT_PROGRESS_INDICATOR_UPDATE = 9,
  OMNI_RA_EVENT_LEADERBOARD_TRACKER_SHOW = 10,
  OMNI_RA_EVENT_LEADERBOARD_TRACKER_HIDE = 11,
  OMNI_RA_EVENT_LEADERBOARD_TRACKER_UPDATE = 12,
  OMNI_RA_EVENT_LEADERBOARD_SCOREBOARD = 13,
  OMNI_RA_EVENT_RESET = 14,
  OMNI_RA_EVENT_GAME_COMPLETED = 15,
  OMNI_RA_EVENT_SERVER_ERROR = 16,
  OMNI_RA_EVENT_DISCONNECTED = 17,
  OMNI_RA_EVENT_RECONNECTED = 18,
  OMNI_RA_EVENT_SUBSET_COMPLETED = 19
} omni_ra_event_type_t;

typedef enum omni_ra_achievement_category_e {
  OMNI_RA_ACHIEVEMENT_CATEGORY_NONE = 0,
  OMNI_RA_ACHIEVEMENT_CATEGORY_CORE = (1 << 0),
  OMNI_RA_ACHIEVEMENT_CATEGORY_UNOFFICIAL = (1 << 1),
  OMNI_RA_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL = 0x3
} omni_ra_achievement_category_t;

typedef enum omni_ra_achievement_grouping_e {
  OMNI_RA_ACHIEVEMENT_GROUPING_LOCK_STATE = 0,
  OMNI_RA_ACHIEVEMENT_GROUPING_PROGRESS = 1
} omni_ra_achievement_grouping_t;

typedef enum omni_ra_leaderboard_grouping_e {
  OMNI_RA_LEADERBOARD_GROUPING_NONE = 0,
  OMNI_RA_LEADERBOARD_GROUPING_TRACKING = 1
} omni_ra_leaderboard_grouping_t;

/* Opaque runtime & async handles */
typedef struct omni_ra_s omni_ra_t;
typedef struct omni_ra_async_s omni_ra_async_t;

/* Static build metadata (module-lifetime const strings) */
typedef struct omni_ra_build_info_s {
  uint32_t struct_size;
  uint32_t abi_major;
  uint32_t abi_minor;
  const char* module_version;
  const char* rcheevos_version;
  const char* git_hash;
} omni_ra_build_info_t;

/* =========================================================================
 * Host Callbacks (HTTP, Memory, VFS/Disc Hashing, Events, Clock, Log)
 * ========================================================================= */
typedef struct omni_ra_http_request_s {
  uint32_t struct_size;
  uint32_t reserved;
  const char* url;
  const char* post_data;    /* NULL for GET */
  const char* content_type; /* NULL if no body */
  const char* user_agent;   /* Active User-Agent configured on instance */
} omni_ra_http_request_t;

typedef struct omni_ra_http_response_s {
  uint32_t struct_size;
  int32_t http_status;      /* HTTP status or omni_ra_http_status_t (<0) */
  const void* body;
  uint32_t body_len;
} omni_ra_http_response_t;

typedef struct omni_ra_file_callbacks_s {
  uint32_t struct_size;
  uint32_t reserved;
  void* (*open)(void* userdata, const char* path_utf8);
  void (*seek)(void* userdata, void* file_handle, int64_t offset, int32_t origin);
  int64_t (*tell)(void* userdata, void* file_handle);
  uint32_t (*read)(void* userdata, void* file_handle, void* buffer, uint32_t requested_bytes);
  void (*close)(void* userdata, void* file_handle);
} omni_ra_file_callbacks_t;

typedef struct omni_ra_cdreader_callbacks_s {
  uint32_t struct_size;
  uint32_t reserved;
  void* (*open_track)(void* userdata, const char* path_utf8, uint32_t track);
  uint32_t (*read_sector)(void* userdata, void* track_handle, uint32_t sector, void* buffer, uint32_t requested_bytes);
  void (*close_track)(void* userdata, void* track_handle);
  uint32_t (*first_track_sector)(void* userdata, void* track_handle);
} omni_ra_cdreader_callbacks_t;

/* Mirrors retro_memory_descriptor using fixed-width ABI-safe fields */
typedef struct omni_ra_memory_descriptor_s {
  uint32_t struct_size;
  uint32_t reserved;
  uint64_t flags;
  void* ptr;
  uint64_t offset;
  uint64_t start;
  uint64_t select;
  uint64_t disconnect;
  uint64_t len;
  const char* addrspace;
} omni_ra_memory_descriptor_t;

/* Raw RETRO_MEMORY_* region (SAVE_RAM=0, RTC=1, SYSTEM_RAM=2, VIDEO_RAM=3) */
typedef struct omni_ra_core_memory_region_s {
  uint32_t struct_size;
  uint32_t memory_type;
  void* data;
  uint64_t size;
} omni_ra_core_memory_region_t;

typedef struct omni_ra_memory_map_s {
  uint32_t struct_size;
  uint32_t console_id; /* RC_CONSOLE_* */
  const omni_ra_memory_descriptor_t* descriptors;
  uint32_t num_descriptors;
  uint32_t num_core_regions;
  const omni_ra_core_memory_region_t* core_regions;
} omni_ra_memory_map_t;

/* Data types delivered in events & list getters */
typedef struct omni_ra_achievement_s {
  uint32_t struct_size;
  uint32_t id;
  uint32_t points;
  int64_t unlock_time;
  float measured_percent;
  float rarity;
  float rarity_hardcore;
  uint8_t state;
  uint8_t category;
  uint8_t bucket;
  uint8_t unlocked;
  uint8_t type;
  uint8_t reserved[3];
  const char* title;
  const char* description;
  const char* badge_name;
  const char* measured_progress;
  const char* badge_url;
  const char* badge_locked_url;
} omni_ra_achievement_t;

typedef struct omni_ra_achievement_bucket_s {
  uint32_t struct_size;
  uint32_t subset_id;
  uint8_t bucket_type;
  uint8_t reserved[3];
  uint32_t num_achievements;
  const char* label;
  const omni_ra_achievement_t* const* achievements;
} omni_ra_achievement_bucket_t;

typedef struct omni_ra_achievement_list_s {
  uint32_t struct_size;
  uint32_t num_buckets;
  const omni_ra_achievement_bucket_t* buckets;
} omni_ra_achievement_list_t;

typedef struct omni_ra_leaderboard_s {
  uint32_t struct_size;
  uint32_t id;
  uint8_t state;
  uint8_t format;
  uint8_t lower_is_better;
  uint8_t reserved;
  const char* title;
  const char* description;
  const char* tracker_value;
} omni_ra_leaderboard_t;

typedef struct omni_ra_leaderboard_bucket_s {
  uint32_t struct_size;
  uint32_t subset_id;
  uint8_t bucket_type;
  uint8_t reserved[3];
  uint32_t num_leaderboards;
  const char* label;
  const omni_ra_leaderboard_t* const* leaderboards;
} omni_ra_leaderboard_bucket_t;

typedef struct omni_ra_leaderboard_list_s {
  uint32_t struct_size;
  uint32_t num_buckets;
  const omni_ra_leaderboard_bucket_t* buckets;
} omni_ra_leaderboard_list_t;

typedef struct omni_ra_leaderboard_tracker_s {
  uint32_t struct_size;
  uint32_t id;
  const char* display;
} omni_ra_leaderboard_tracker_t;

typedef struct omni_ra_leaderboard_scoreboard_entry_s {
  uint32_t struct_size;
  uint32_t rank;
  const char* username;
  const char* score;
} omni_ra_leaderboard_scoreboard_entry_t;

typedef struct omni_ra_leaderboard_scoreboard_s {
  uint32_t struct_size;
  uint32_t leaderboard_id;
  uint32_t new_rank;
  uint32_t num_entries;
  uint32_t num_top_entries;
  const char* submitted_score;
  const char* best_score;
  const omni_ra_leaderboard_scoreboard_entry_t* top_entries;
} omni_ra_leaderboard_scoreboard_t;

typedef struct omni_ra_subset_s {
  uint32_t struct_size;
  uint32_t id;
  uint32_t num_achievements;
  uint32_t num_leaderboards;
  const char* title;
  const char* badge_name;
  const char* badge_url;
} omni_ra_subset_t;

typedef struct omni_ra_server_error_s {
  uint32_t struct_size;
  int32_t result;
  uint32_t related_id;
  const char* error_message;
  const char* api;
} omni_ra_server_error_t;

/* Valid ONLY during host->on_event invocation */
typedef struct omni_ra_event_s {
  uint32_t struct_size;
  uint32_t type; /* omni_ra_event_type_t */
  const omni_ra_achievement_t* achievement;
  const omni_ra_leaderboard_t* leaderboard;
  const omni_ra_leaderboard_tracker_t* leaderboard_tracker;
  const omni_ra_leaderboard_scoreboard_t* leaderboard_scoreboard;
  const omni_ra_server_error_t* server_error;
  const omni_ra_subset_t* subset;
} omni_ra_event_t;

/* Caller-owned snapshots (strings stored inline, no module-owned lifetime) */
typedef struct omni_ra_user_info_s {
  uint32_t struct_size;
  uint32_t score;
  uint32_t score_softcore;
  uint32_t num_unread_messages;
  int64_t avatar_last_updated;
  char username[64];
  char display_name[64];
  char token[64];
  char avatar_url[256];
} omni_ra_user_info_t;

typedef struct omni_ra_game_info_s {
  uint32_t struct_size;
  uint32_t id;
  uint32_t console_id;
  char title[256];
  char hash[33];
  char badge_name[32];
  char badge_url[256];
} omni_ra_game_info_t;

typedef struct omni_ra_user_game_summary_s {
  uint32_t struct_size;
  uint32_t num_core_achievements;
  uint32_t num_unofficial_achievements;
  uint32_t num_unlocked_achievements;
  uint32_t num_unsupported_achievements;
  uint32_t points_core;
  uint32_t points_unlocked;
  int64_t beaten_time;
  int64_t completed_time;
} omni_ra_user_game_summary_t;

/* Host callback table passed to omni_ra_create() */
typedef struct omni_ra_host_s {
  uint32_t struct_size;
  uint32_t host_abi_version; /* OMNI_RA_MAKE_ABI_VERSION(major, minor) */
  void* userdata;

  /* Required callbacks */
  uint64_t (*get_time_ms)(void* userdata);
  void (*http_request)(void* userdata, uint64_t request_id, const omni_ra_http_request_t* req);
  void (*on_event)(void* userdata, const omni_ra_event_t* event);

  /* Optional memory fallback when omni_ra_set_memory_map() is not used.
   * Must perform pure non-destructive reads; returns byte count read. */
  uint32_t (*read_memory)(void* userdata, uint32_t address, uint8_t* buffer, uint32_t num_bytes);

  /* Optional callbacks */
  void (*log_message)(void* userdata, int32_t level, const char* message);
  const omni_ra_file_callbacks_t* file_callbacks;
  const omni_ra_cdreader_callbacks_t* cdreader_callbacks;
} omni_ra_host_t;

typedef void (*omni_ra_async_cb_t)(void* cb_userdata, int32_t result, const char* error_message);

/* =========================================================================
 * Exported Symbol Surface (ALL symbols start with omni_ra_)
 * ========================================================================= */

/* Thread-safe / Stateless metadata */
OMNI_RA_EXPORT uint32_t omni_ra_abi_version(void);
OMNI_RA_EXPORT int32_t omni_ra_get_build_info(omni_ra_build_info_t* out_info);

/* Lifecycle */
OMNI_RA_EXPORT omni_ra_t* omni_ra_create(const omni_ra_host_t* host);
OMNI_RA_EXPORT void omni_ra_destroy(omni_ra_t* ra);

/* Configuration (hardcore may also toggle during play -> raises OMNI_RA_EVENT_RESET) */
OMNI_RA_EXPORT int32_t omni_ra_set_user_agent(omni_ra_t* ra, const char* user_agent);
OMNI_RA_EXPORT int32_t omni_ra_set_host(omni_ra_t* ra, const char* hostname);
OMNI_RA_EXPORT void omni_ra_set_log_level(omni_ra_t* ra, int32_t level);
OMNI_RA_EXPORT void omni_ra_set_hardcore(omni_ra_t* ra, int32_t enabled);
OMNI_RA_EXPORT int32_t omni_ra_get_hardcore(const omni_ra_t* ra);
OMNI_RA_EXPORT void omni_ra_set_encore_mode(omni_ra_t* ra, int32_t enabled);
OMNI_RA_EXPORT void omni_ra_set_unofficial_enabled(omni_ra_t* ra, int32_t enabled);
OMNI_RA_EXPORT void omni_ra_set_spectator_mode(omni_ra_t* ra, int32_t enabled);

/* rc_libretro capability & core option compliance helpers (stateless) */
OMNI_RA_EXPORT int32_t omni_ra_is_system_allowed(const char* library_name, uint32_t console_id);
OMNI_RA_EXPORT int32_t omni_ra_is_setting_allowed(const char* library_name, uint32_t console_id,
                                                  const char* setting_key, const char* setting_value);

/* Authentication (never caches password; completes asynchronously via HTTP) */
OMNI_RA_EXPORT omni_ra_async_t* omni_ra_login_with_password(
    omni_ra_t* ra, const char* username, const char* password,
    omni_ra_async_cb_t cb, void* cb_userdata);
OMNI_RA_EXPORT omni_ra_async_t* omni_ra_login_with_token(
    omni_ra_t* ra, const char* username, const char* token,
    omni_ra_async_cb_t cb, void* cb_userdata);
OMNI_RA_EXPORT void omni_ra_logout(omni_ra_t* ra);
OMNI_RA_EXPORT int32_t omni_ra_get_user_info(const omni_ra_t* ra, omni_ra_user_info_t* out_user);
OMNI_RA_EXPORT void omni_ra_cancel_async(omni_ra_t* ra, omni_ra_async_t* handle);

/* Async HTTP completion (thread-safe while ra has not entered omni_ra_destroy) */
OMNI_RA_EXPORT void omni_ra_http_complete(
    omni_ra_t* ra, uint64_t request_id, const omni_ra_http_response_t* response);

/* Game identification, memory map & lifecycle
 * omni_ra_set_memory_map(ra, NULL) MUST be called before core memory pointers become invalid. */
OMNI_RA_EXPORT int32_t omni_ra_set_memory_map(omni_ra_t* ra, const omni_ra_memory_map_t* map);
OMNI_RA_EXPORT int32_t omni_ra_dump_memory_regions(
    const omni_ra_t* ra, char* buffer, uint32_t buffer_size, uint32_t* out_len);
OMNI_RA_EXPORT omni_ra_async_t* omni_ra_begin_identify_and_load_game(
    omni_ra_t* ra, uint32_t console_id, const char* file_path_utf8,
    const uint8_t* data, uint64_t data_size,
    omni_ra_async_cb_t cb, void* cb_userdata);
OMNI_RA_EXPORT omni_ra_async_t* omni_ra_begin_load_game_hash(
    omni_ra_t* ra, const char* hash, omni_ra_async_cb_t cb, void* cb_userdata);
OMNI_RA_EXPORT omni_ra_async_t* omni_ra_begin_identify_and_change_media(
    omni_ra_t* ra, const char* file_path_utf8, const uint8_t* data, uint64_t data_size,
    omni_ra_async_cb_t cb, void* cb_userdata);
OMNI_RA_EXPORT void omni_ra_unload_game(omni_ra_t* ra);
OMNI_RA_EXPORT int32_t omni_ra_is_game_loaded(const omni_ra_t* ra);
OMNI_RA_EXPORT int32_t omni_ra_get_load_state(const omni_ra_t* ra);

/* Emulation thread runtime */
OMNI_RA_EXPORT void omni_ra_do_frame(omni_ra_t* ra);
OMNI_RA_EXPORT void omni_ra_idle(omni_ra_t* ra);
OMNI_RA_EXPORT void omni_ra_reset(omni_ra_t* ra);
OMNI_RA_EXPORT int32_t omni_ra_is_processing_required(omni_ra_t* ra);
OMNI_RA_EXPORT int32_t omni_ra_can_pause(omni_ra_t* ra, uint32_t* out_frames_remaining);
OMNI_RA_EXPORT uint32_t omni_ra_progress_size(omni_ra_t* ra);
OMNI_RA_EXPORT int32_t omni_ra_serialize_progress(omni_ra_t* ra, uint8_t* buffer, uint32_t buffer_size);
OMNI_RA_EXPORT int32_t omni_ra_deserialize_progress(omni_ra_t* ra, const uint8_t* buffer, uint32_t buffer_size);

/* Metadata & UI queries */
OMNI_RA_EXPORT int32_t omni_ra_get_game_info(const omni_ra_t* ra, omni_ra_game_info_t* out_game);
OMNI_RA_EXPORT int32_t omni_ra_get_user_game_summary(const omni_ra_t* ra, omni_ra_user_game_summary_t* out_summary);
OMNI_RA_EXPORT int32_t omni_ra_has_rich_presence(omni_ra_t* ra);
OMNI_RA_EXPORT int32_t omni_ra_get_rich_presence_message(
    omni_ra_t* ra, char* buffer, uint32_t buffer_size, uint32_t* out_len);

OMNI_RA_EXPORT omni_ra_achievement_list_t* omni_ra_get_achievement_list(
    omni_ra_t* ra, int32_t category, int32_t grouping);
OMNI_RA_EXPORT void omni_ra_free_achievement_list(omni_ra_achievement_list_t* list);

OMNI_RA_EXPORT omni_ra_leaderboard_list_t* omni_ra_get_leaderboard_list(
    omni_ra_t* ra, int32_t grouping);
OMNI_RA_EXPORT void omni_ra_free_leaderboard_list(omni_ra_leaderboard_list_t* list);

#ifdef __cplusplus
}
#endif
#endif /* OMNI_RA_H */
