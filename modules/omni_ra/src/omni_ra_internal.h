#ifndef OMNI_RA_INTERNAL_H
#define OMNI_RA_INTERNAL_H

#if !defined(_WIN32) && (!defined(_POSIX_C_SOURCE) || (_POSIX_C_SOURCE - 0) < 200809L)
  #undef _POSIX_C_SOURCE
  #define _POSIX_C_SOURCE 200809L
#endif

#include "omni_ra.h"
#include "rc_client.h"
#include "rc_compat.h"
#include "rc_consoles.h"
#include "rc_error.h"
#include "rc_hash.h"
#include "rc_libretro.h"
#include "rc_version.h"

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#if defined(_MSC_VER)
  #define OMNI_THREAD_LOCAL __declspec(thread)
#elif defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
  #define OMNI_THREAD_LOCAL _Thread_local
#else
  #define OMNI_THREAD_LOCAL __thread
#endif

typedef struct omni_ra_http_pending_s {
  uint64_t request_id;
  rc_client_server_callback_t callback;
  void* callback_data;
  struct omni_ra_http_pending_s* next;
} omni_ra_http_pending_t;

typedef struct omni_ra_async_req_s {
  omni_ra_t* ra;
  rc_client_async_handle_t* rc_handle;
  omni_ra_async_cb_t cb;
  void* cb_userdata;
  int completed;
  int caller_returned;
} omni_ra_async_req_t;

struct omni_ra_s {
  uint32_t host_abi_version;
  omni_ra_host_t host;
  omni_ra_file_callbacks_t file_cbs;
  omni_ra_cdreader_callbacks_t cdreader_cbs;
  int has_file_cbs;
  int has_cdreader_cbs;

  rc_client_t* client;
  char* user_agent;

  rc_mutex_t http_lock;
  uint64_t next_request_id;
  omni_ra_http_pending_t* pending_http;
  int destroying;

  rc_mutex_t mem_lock;
  rc_libretro_memory_regions_t mem_regions;
  int has_mem_map;
  uint32_t mapped_console_id;
  uint32_t active_console_id;
  int32_t log_level;
};

#endif /* OMNI_RA_INTERNAL_H */
