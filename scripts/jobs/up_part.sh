#!/bin/bash
# Side-upload a serialized export part (zip) with many S3 connections while the export re-converts.
#   up_part.sh <zip> <part key, e.g. part:Llama3_2_3B_Part3_Of_3_w4a16.aimet>
# The resume hook waits on ~/iv/state/uploading_<key> and then reuses the cached model id.
JOB=up_part; source $HOME/iv/jobs/common.sh
Z=$1; KEY=$2
LOCK=$ST/uploading_$(echo "$KEY" | tr ':/' '__')
touch "$LOCK"
for i in 1 2 3; do
  if IV_SIDE_UPLOAD=1 $HOME/venvs/qaihm_pin/bin/python -c "import qai_hub as hub,sys; m=hub.upload_model(sys.argv[1]); print('UPLOADED', m.model_id)" "$Z"; then
    rm -f "$LOCK"; status "side upload OK: $KEY"; exit 0
  fi
  status "side upload attempt $i failed: $KEY"; sleep 20
done
rm -f "$LOCK"; status "side upload GAVE UP: $KEY (export will upload it itself)"; exit 1
