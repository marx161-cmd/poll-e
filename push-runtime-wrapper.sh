#!/usr/bin/env bash
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-100.69.13.12:5555}"

adb -s "$ADB_SERIAL" push "$(dirname "$0")/termux-poll-e-gemma3-1b-npu" \
  /data/local/tmp/poll-e-gemma3-1b-npu
adb -s "$ADB_SERIAL" shell chmod 755 /data/local/tmp/poll-e-gemma3-1b-npu
adb -s "$ADB_SERIAL" shell ls -lh /data/local/tmp/poll-e-gemma3-1b-npu
