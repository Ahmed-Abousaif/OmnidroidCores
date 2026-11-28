# `libomnidroid_ra.so` C ABI Contract (`omni_ra.h`)

## Versioning & Compatibility Policy

- `omni_ra_abi_version()` packs `(major << 16) | (minor & 0xFFFF)`.
- **Current ABI**: `1.0` (`OMNI_RA_ABI_VERSION_MAJOR = 1`, `OMNI_RA_ABI_VERSION_MINOR = 0`).
- **Major bump rules** (breaking; app refuses module if `OMNI_RA_ABI_MAJOR(ver)` is unsupported):
  - Removing or renaming any exported `omni_ra_*` symbol.
  - Changing parameters or return type of an existing `omni_ra_*` function.
  - Reordering, resizing, or removing any existing field in an ABI struct.
  - Changing semantics of an existing enum value.
- **Minor bump rules** (additive; backward and forward compatible):
  - Appending new optional fields to the **end** of an existing ABI struct.
  - Adding new enum values.
  - Exporting new optional `omni_ra_*` functions.

## Struct Evolution (`struct_size`)

Every struct crossing the ABI boundary starts with `uint32_t struct_size;`.
- Callers initialize every input or output struct with `s.struct_size = sizeof(s)`.
- For **inputs** (`omni_ra_host_t`, `omni_ra_http_response_t`, `omni_ra_memory_map_t`, etc.), the module checks `struct_size` against required baseline fields and treats trailing fields beyond `s->struct_size` as omitted/zero (`NULL`).
- For **outputs** (`omni_ra_build_info_t`, `omni_ra_user_info_t`, `omni_ra_game_info_t`, `omni_ra_user_game_summary_t`), the module writes only up to `out->struct_size` bytes.
- Every field uses fixed-width types (`uint8_t`, `int32_t`, `uint32_t`, `int64_t`, `uint64_t`, `float`) and pointers—never `size_t`, `_Bool`, `time_t`, or C++ types.
- No structs are returned by value.

## Pointer & Lifetime Rules

- `omni_ra_build_info_t`: strings point to static module-lifetime literals valid until `dlclose`.
- `omni_ra_host_t`: host may stack-allocate the struct for `omni_ra_create()`, because the module copies callback pointers and `userdata`; if `file_callbacks` or `cdreader_callbacks` are non-NULL, the module copies their function tables inside `omni_ra_create()`.
- `omni_ra_event_t` & `omni_ra_http_request_t`: strings and nested pointers are valid **only** for the duration of the synchronous callback invocation. Hosts must copy any data retained asynchronously.
- `omni_ra_user_info_t`, `omni_ra_game_info_t`, `omni_ra_get_rich_presence_message`, `omni_ra_dump_memory_regions`: copy inline into caller-supplied buffers.
- `omni_ra_get_achievement_list` / `omni_ra_free_achievement_list` and `omni_ra_get_leaderboard_list` / `omni_ra_free_leaderboard_list`: module allocates self-contained snapshots whose strings remain valid until the matching `free` function is called.
- `omni_ra_set_memory_map()`: core memory `ptr`/`data` pointers are assumed valid by the module until replaced by another `omni_ra_set_memory_map()` call or cleared with `omni_ra_set_memory_map(ra, NULL)`. Always clear before `retro_unload_game()` or core destruction.

## Thread Safety Contract

| Function | Permitted Threads & Serialization |
| --- | --- |
| `omni_ra_abi_version`, `omni_ra_get_build_info`, `omni_ra_is_system_allowed`, `omni_ra_is_setting_allowed` | Any thread; stateless/read-only. |
| `omni_ra_create`, `omni_ra_destroy` | Control/session lifecycle thread. No other calls on `ra` (including `omni_ra_http_complete` or `omni_ra_do_frame`) may run concurrently with or after `omni_ra_destroy`. Inside `omni_ra_destroy`, any still-tracked HTTP requests are failed safely before tearing down `rc_client`. |
| `omni_ra_do_frame`, `omni_ra_reset`, `omni_ra_progress_size`, `omni_ra_serialize_progress`, `omni_ra_deserialize_progress`, `omni_ra_set_memory_map` | Emulation thread (or external emulation mutex). Reads via `omni_ra_set_memory_map` / `host->read_memory` are internally synchronized against map updates/invalidation. |
| `omni_ra_idle`, `omni_ra_can_pause`, `omni_ra_begin_*`, `omni_ra_unload_game`, queries | Safe from UI/emulation/worker threads via internal `rc_client` mutex, provided `ra` stays alive. Never invoke frame-blocking operations on the UI/main thread. |
| `omni_ra_http_complete` | Any background HTTP worker thread while `ra` is alive and not destroying. Must eventually be called for every request emitted by `host->http_request` unless `omni_ra_destroy` aborts the instance. |

## Symbol Hygiene

On Android and Linux, only `omni_ra_*` symbols are exported (`-fvisibility=hidden`, `--version-script=exports.map`, `--exclude-libs,ALL`). Android shared library dependencies are strictly limited to `libc`, `libm`, and `libdl` (zero `libc++`, `liblog`, `libandroid`, or JNI dependencies).
