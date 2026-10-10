#!/bin/bash
# Build iv_cli + libiv_native.so (Genie + whisper.cpp) and stage runtime (native/build_android.sh).
JOB=build_native; source $HOME/iv/jobs/common.sh
until [ -d "$HOME/iv/android-ndk-r27c" ] && [ -f "$HOME/iv/whisper.cpp/CMakeLists.txt" ] && ls -d $HOME/qairt/qairt/2.50* >/dev/null 2>&1; do
  status "waiting for toolchains"; sleep 120
done
sed -i 's/\r$//' "$REPO/native/build_android.sh"
for i in 1 2; do
  if bash "$REPO/native/build_android.sh"; then status "native build OK"; mark_done; exit 0; fi
  sleep 10
done
mark_failed "native build failed twice — see ~/iv/logs/build_native.log"
