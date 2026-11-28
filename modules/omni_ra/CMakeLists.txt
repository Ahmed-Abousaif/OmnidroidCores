cmake_minimum_required(VERSION 3.22.1)
project(omni_ra LANGUAGES C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

option(OMNI_RA_BUILD_TESTS "Build host unit test suite" OFF)
set(OMNI_RA_VERSION "1.0.0" CACHE STRING "Module semantic version")
set(OMNI_RA_GIT_HASH "unknown" CACHE STRING "Git commit hash")

set(RCHEEVOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/rcheevos")
set(LIBRETRO_INC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libretro")

set(RCHEEVOS_SOURCES
  "${RCHEEVOS_DIR}/src/rc_client.c"
  "${RCHEEVOS_DIR}/src/rc_compat.c"
  "${RCHEEVOS_DIR}/src/rc_libretro.c"
  "${RCHEEVOS_DIR}/src/rc_util.c"
  "${RCHEEVOS_DIR}/src/rc_version.c"
  "${RCHEEVOS_DIR}/src/rapi/rc_api_common.c"
  "${RCHEEVOS_DIR}/src/rapi/rc_api_editor.c"
  "${RCHEEVOS_DIR}/src/rapi/rc_api_info.c"
  "${RCHEEVOS_DIR}/src/rapi/rc_api_runtime.c"
  "${RCHEEVOS_DIR}/src/rapi/rc_api_user.c"
  "${RCHEEVOS_DIR}/src/rcheevos/alloc.c"
  "${RCHEEVOS_DIR}/src/rcheevos/condition.c"
  "${RCHEEVOS_DIR}/src/rcheevos/condset.c"
  "${RCHEEVOS_DIR}/src/rcheevos/consoleinfo.c"
  "${RCHEEVOS_DIR}/src/rcheevos/format.c"
  "${RCHEEVOS_DIR}/src/rcheevos/lboard.c"
  "${RCHEEVOS_DIR}/src/rcheevos/memref.c"
  "${RCHEEVOS_DIR}/src/rcheevos/operand.c"
  "${RCHEEVOS_DIR}/src/rcheevos/rc_validate.c"
  "${RCHEEVOS_DIR}/src/rcheevos/richpresence.c"
  "${RCHEEVOS_DIR}/src/rcheevos/runtime.c"
  "${RCHEEVOS_DIR}/src/rcheevos/runtime_progress.c"
  "${RCHEEVOS_DIR}/src/rcheevos/trigger.c"
  "${RCHEEVOS_DIR}/src/rcheevos/value.c"
  "${RCHEEVOS_DIR}/src/rhash/aes.c"
  "${RCHEEVOS_DIR}/src/rhash/cdreader.c"
  "${RCHEEVOS_DIR}/src/rhash/hash.c"
  "${RCHEEVOS_DIR}/src/rhash/hash_disc.c"
  "${RCHEEVOS_DIR}/src/rhash/hash_encrypted.c"
  "${RCHEEVOS_DIR}/src/rhash/hash_rom.c"
  "${RCHEEVOS_DIR}/src/rhash/hash_zip.c"
  "${RCHEEVOS_DIR}/src/rhash/md5.c"
)

set(OMNI_RA_SOURCES
  "${CMAKE_CURRENT_SOURCE_DIR}/src/omni_ra.c"
  ${RCHEEVOS_SOURCES}
)

if(ANDROID)
  set(OMNI_RA_LIB_TYPE SHARED)
elseif(WIN32)
  set(OMNI_RA_LIB_TYPE STATIC)
else()
  set(OMNI_RA_LIB_TYPE SHARED)
endif()

add_library(omnidroid_ra ${OMNI_RA_LIB_TYPE} ${OMNI_RA_SOURCES})
set_target_properties(omnidroid_ra PROPERTIES
  OUTPUT_NAME "omnidroid_ra"
  C_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
)

target_include_directories(omnidroid_ra
  PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
  PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${RCHEEVOS_DIR}/include"
    "${RCHEEVOS_DIR}/src"
    "${LIBRETRO_INC_DIR}"
)

target_compile_definitions(omnidroid_ra PRIVATE
  RC_CLIENT_SUPPORTS_HASH=1
  RC_DISABLE_LUA=1
  OMNI_RA_VERSION_STR="${OMNI_RA_VERSION}"
  OMNI_RA_GIT_HASH_STR="${OMNI_RA_GIT_HASH}"
)

if(MSVC)
  target_compile_definitions(omnidroid_ra PRIVATE _CRT_SECURE_NO_WARNINGS)
  target_compile_options(omnidroid_ra PRIVATE /W3)
else()
  target_compile_definitions(omnidroid_ra PRIVATE
    _LARGEFILE64_SOURCE
    _FILE_OFFSET_BITS=64
    _POSIX_C_SOURCE=200809L
  )
  target_compile_options(omnidroid_ra PRIVATE
    -fvisibility=hidden
    -Wall
    -Wextra
  )
  if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(omnidroid_ra PRIVATE -Os -ffunction-sections -fdata-sections)
  endif()
endif()

if(ANDROID)
  target_link_options(omnidroid_ra PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    "-Wl,--exclude-libs,ALL"
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--no-undefined"
  )
  target_link_libraries(omnidroid_ra PRIVATE m dl)
elseif(UNIX AND NOT APPLE)
  target_compile_options(omnidroid_ra PRIVATE -include strings.h)
  target_link_options(omnidroid_ra PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--no-undefined"
  )
  target_link_libraries(omnidroid_ra PRIVATE m dl pthread)
endif()

if(OMNI_RA_BUILD_TESTS)
  enable_testing()
  add_executable(test_omni_ra "${CMAKE_CURRENT_SOURCE_DIR}/tests/test_omni_ra.c")
  target_include_directories(test_omni_ra PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${LIBRETRO_INC_DIR}"
  )
  if(MSVC)
    target_compile_definitions(test_omni_ra PRIVATE _CRT_SECURE_NO_WARNINGS)
  else()
    target_compile_definitions(test_omni_ra PRIVATE _POSIX_C_SOURCE=200809L)
    target_compile_options(test_omni_ra PRIVATE -Wall -Wextra)
  endif()
  target_link_libraries(test_omni_ra PRIVATE omnidroid_ra)
  add_test(NAME omni_ra_tests COMMAND test_omni_ra)
endif()
