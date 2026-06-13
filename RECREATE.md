# Recreate Poll-E Runtime State

These notes recreate the current source state without relying on the local
2 GB recovery tree being present in this repository.

## 1. Prepare LiteRT-LM

Use the preserved working copy if available:

```bash
cd /home/comrade/homelab/Poll-E/recovery-2026-06-13/LiteRT-LM-working-copy
```

If starting fresh, clone the upstream LiteRT-LM repository first, then apply the
patch from this repo:

```bash
git clone https://github.com/google-ai-edge/LiteRT-LM.git
cd LiteRT-LM
git apply /path/to/Poll-E/source-patches/litert-lm-poll-e-worker-2026-06-13.patch
```

The patch assumes the same upstream neighborhood as the local 2026-06-13
working copy. If upstream moved, apply manually around
`runtime/engine/litert_lm_main.cc` and the NPU stub guard in
`runtime/executor/llm_litert_npu_compiled_model_executor.cc`.

## 2. Build

Use Bazel, not the standalone CMake recipe.

```bash
export ANDROID_NDK_HOME=/home/comrade/lib/android-ndk-r29
bazelisk build --config=android_arm64 //runtime/engine:litert_lm_main
```

NDK r26d failed in the Rust `cxx`/libc++ path on this machine. NDK r29 built the
current worker successfully.

## 3. Stage On Phone

The current tested deployment folder is:

```text
/data/local/tmp/poll-e-worker-test
```

Push the binary:

```bash
adb -s 100.69.13.12:5555 shell 'mkdir -p /data/local/tmp/poll-e-worker-test'
adb -s 100.69.13.12:5555 push bazel-bin/runtime/engine/litert_lm_main \
  /data/local/tmp/poll-e-worker-test/litert_lm_main
adb -s 100.69.13.12:5555 shell \
  'chmod 755 /data/local/tmp/poll-e-worker-test/litert_lm_main'
```

The working NPU support libraries came from the known-good LiteRT-LM test
folder:

```bash
adb -s 100.69.13.12:5555 shell '
  cp /data/local/tmp/litertlm-test/libLiteRtDispatch_GoogleTensor.so \
     /data/local/tmp/poll-e-worker-test/
  cp /data/local/tmp/litertlm-test/libGemmaModelConstraintProvider.so \
     /data/local/tmp/poll-e-worker-test/
'
```

The tested model path is:

```text
/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm
```

It was copied out of Termux private storage because the standalone shell runner
could not read the original app-private model path.

## 4. Smoke Test

One-shot:

```bash
adb -s 100.69.13.12:5555 shell '
  cd /data/local/tmp/poll-e-worker-test &&
  LD_LIBRARY_PATH=/data/local/tmp/poll-e-worker-test ./litert_lm_main \
    --backend=npu \
    --model_path=/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm \
    --input_prompt="What is 2+2?"
'
```

Worker:

```bash
adb -s 100.69.13.12:5555 shell '
  cd /data/local/tmp/poll-e-worker-test &&
  printf "%s\n%s\n" \
    "App: Messages. Focused input is empty. Recent incoming message: Want coffee?" \
    "__quit__" |
  LD_LIBRARY_PATH=/data/local/tmp/poll-e-worker-test ./litert_lm_main \
    --backend=npu \
    --model_path=/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm \
    --poll_e_worker \
    --poll_e_profile_file=/data/local/tmp/poll-e-worker-test/profile.txt \
    --poll_e_max_output_tokens=24
'
```

Expected shape:

```text
POLL_E_READY
POLL_E_BEGIN
...
POLL_E_END
```

Native dispatch may print a startup line before `POLL_E_READY`; callers should
ignore output until ready.
