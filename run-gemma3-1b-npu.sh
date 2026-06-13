#!/usr/bin/env bash
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-100.69.13.12:5555}"
PROMPT="${*:-What is 2+2?}"

adb -s "$ADB_SERIAL" shell \
  "LD_LIBRARY_PATH=/data/local/tmp/litertlm-test \
   /data/local/tmp/litertlm-test/litert_lm_main \
   --backend=npu \
   --model_path=/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm \
   --input_prompt=\"$(printf '%s' "$PROMPT" | sed 's/"/\\"/g')\""
