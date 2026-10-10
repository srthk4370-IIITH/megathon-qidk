cmake_minimum_required(VERSION 3.22)
project(iv_native CXX C)

# IncidentVoice native core (PRD §18, rev 1.1):
#   iv_core   static: engine facade, whisper.cpp ASR, Genie LLM
#   iv_native shared: JNI for com.megathon.incidentvoice.engine.NativeBridge (Plan A)
#   iv_cli    executable: on-device CLI / Plan B worker (native/IV_CLI.md)
#
# Android build (from WSL):
#   cmake -B build-android -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a \
#     -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
#     -DQAIRT_ROOT=$HOME/qairt/qairt/2.50.0.260828 -DWHISPER_DIR=$HOME/iv/whisper.cpp
#   cmake --build build-android -j

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(IV_WITH_GENIE "Link Qualcomm Genie (needs QAIRT_ROOT)" ON)
option(IV_BUILD_JNI "Build libiv_native.so" ON)
option(IV_BUILD_CLI "Build iv_cli" ON)
set(QAIRT_ROOT "$ENV{QAIRT_ROOT}" CACHE PATH "QAIRT SDK root (contains include/Genie, lib/aarch64-android)")
set(WHISPER_DIR "$ENV{HOME}/iv/whisper.cpp" CACHE PATH "whisper.cpp source checkout")

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# ---- whisper.cpp (static, CPU only) ----
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
if(ANDROID)
  # Snapdragon 8 Elite (Oryon): dotprod + fp16 + i8mm
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
endif()
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

# ---- core ----
add_library(iv_core STATIC
  src/engine.cpp
  src/asr_engine.cpp
  src/wav.cpp)
target_include_directories(iv_core PUBLIC src third_party)
target_link_libraries(iv_core PUBLIC whisper)

if(IV_WITH_GENIE)
  if(NOT EXISTS "${QAIRT_ROOT}/include/Genie/GenieDialog.h")
    message(FATAL_ERROR "IV_WITH_GENIE=ON but ${QAIRT_ROOT}/include/Genie/GenieDialog.h not found; set -DQAIRT_ROOT or -DIV_WITH_GENIE=OFF")
  endif()
  file(READ "${QAIRT_ROOT}/include/Genie/GenieDialog.h" _gd)
  set(_has_sampler 0)
  set(_has_signal 0)
  if(EXISTS "${QAIRT_ROOT}/include/Genie/GenieSampler.h" AND _gd MATCHES "GenieDialog_getSampler")
    set(_has_sampler 1)
  endif()
  if(_gd MATCHES "GenieDialog_signal")
    set(_has_signal 1)
  endif()
  add_library(genie SHARED IMPORTED)
  set_target_properties(genie PROPERTIES IMPORTED_LOCATION "${QAIRT_ROOT}/lib/aarch64-android/libGenie.so")
  target_sources(iv_core PRIVATE src/llm_engine.cpp)
  target_include_directories(iv_core PUBLIC "${QAIRT_ROOT}/include/Genie")
  target_link_libraries(iv_core PUBLIC genie)
  target_compile_definitions(iv_core PUBLIC IV_WITH_GENIE=1 IV_GENIE_HAS_SAMPLER=${_has_sampler} IV_GENIE_HAS_SIGNAL=${_has_signal})
  message(STATUS "Genie: sampler API=${_has_sampler} signal API=${_has_signal}")
else()
  target_compile_definitions(iv_core PUBLIC IV_WITH_GENIE=0)
endif()

if(ANDROID)
  target_link_libraries(iv_core PUBLIC log)
endif()

if(IV_BUILD_JNI)
  add_library(iv_native SHARED jni/iv_jni.cpp)
  target_link_libraries(iv_native PRIVATE iv_core)
  target_link_options(iv_native PRIVATE -Wl,--gc-sections)
endif()

if(IV_BUILD_CLI)
  add_executable(iv_cli cli/main.cpp)
  target_link_libraries(iv_cli PRIVATE iv_core)
endif()
