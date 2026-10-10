#!/bin/bash
# Build iv_cli + libiv_native.so for arm64 (Snapdragon 8 Elite) and stage runtime files.
# Usage (WSL): bash native/build_android.sh            → outputs in ~/iv/stage and android/engine/src/main/jniLibs/arm64-v8a
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK:-$HOME/iv/android-ndk-r27c}"
QAIRT_ROOT="${QAIRT_ROOT:-$(ls -d $HOME/qairt/qairt/2.50* 2>/dev/null | head -1)}"
WHISPER_DIR="${WHISPER_DIR:-$HOME/iv/whisper.cpp}"
BUILD="$HOME/iv/build-android"
STAGE="$HOME/iv/stage"
WITH_GENIE=ON
[ -f "$QAIRT_ROOT/include/Genie/GenieDialog.h" ] || { echo "WARN: QAIRT not found ($QAIRT_ROOT) → building WITHOUT Genie"; WITH_GENIE=OFF; }
[ -d "$NDK" ] || { echo "NDK missing: $NDK"; exit 1; }
[ -f "$WHISPER_DIR/CMakeLists.txt" ] || { echo "whisper.cpp missing: $WHISPER_DIR"; exit 1; }

# Build from a Linux-side copy of native/ (building on /mnt/c is slow).
rsync -a --delete "$REPO/native/" "$HOME/iv/native_src/"
cmake -S "$HOME/iv/native_src" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DIV_WITH_GENIE=$WITH_GENIE -DQAIRT_ROOT="$QAIRT_ROOT" -DWHISPER_DIR="$WHISPER_DIR"
cmake --build "$BUILD" -j"$(nproc)"

STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
mkdir -p "$STAGE"/{bin,lib,dsp}
cp "$BUILD/iv_cli" "$STAGE/bin/" && "$STRIP" "$STAGE/bin/iv_cli"
cp "$BUILD/libiv_native.so" "$STAGE/lib/" && "$STRIP" "$STAGE/lib/libiv_native.so"
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$STAGE/lib/"

if [ "$WITH_GENIE" = ON ]; then
  A="$QAIRT_ROOT/lib/aarch64-android"
  for f in libGenie.so libQnnHtp.so libQnnHtpV79Stub.so libQnnSystem.so libQnnHtpNetRunExtensions.so; do
    [ -f "$A/$f" ] && cp "$A/$f" "$STAGE/lib/"
  done
  cp "$QAIRT_ROOT/lib/hexagon-v79/unsigned/"* "$STAGE/dsp/"            # DSP side, kept separate (PRD §18.2)
  cp "$QAIRT_ROOT/bin/aarch64-android/genie-t2t-run" "$STAGE/bin/" 2>/dev/null || true
fi

# App packaging: ARM libs into the engine module (DSP skels are pushed to the models dir instead).
JL="$REPO/android/engine/src/main/jniLibs/arm64-v8a"
mkdir -p "$JL"
for f in libiv_native.so libc++_shared.so libGenie.so libQnnHtp.so libQnnHtpV79Stub.so libQnnSystem.so; do
  [ -f "$STAGE/lib/$f" ] && cp "$STAGE/lib/$f" "$JL/"
done
ls -la "$STAGE/bin" "$STAGE/lib" "$JL"
echo "BUILD_OK genie=$WITH_GENIE"
