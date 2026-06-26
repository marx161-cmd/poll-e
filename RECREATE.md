# Recreate Poll-E Runtime State

These notes recreate the current source state without relying on the local
2 GB recovery tree being present in this repository.

## 1. Prepare LiteRT-LM (worker binary)

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

## 2. Build worker binary

Use Bazel, not the standalone CMake recipe.

```bash
export ANDROID_NDK_HOME=/home/comrade/lib/android-ndk-r29
bazelisk build --config=android_arm64 //runtime/engine:litert_lm_main
```

NDK r26d failed in the Rust `cxx`/libc++ path on this machine. NDK r29 built the
current worker successfully.

## 3. Stage worker in jniLibs (APK packaging)

Copy the built worker binary and NPU dispatch libraries into
`poll-e-service/app/src/main/jniLibs/arm64-v8a/`:

```bash
cp bazel-bin/runtime/engine/litert_lm_main \
  poll-e-service/app/src/main/jniLibs/arm64-v8a/libpoll_e_worker.so
cp /path/to/libLiteRtDispatch_GoogleTensor.so \
  poll-e-service/app/src/main/jniLibs/arm64-v8a/
cp /path/to/libGemmaModelConstraintProvider.so \
  poll-e-service/app/src/main/jniLibs/arm64-v8a/
```

These are version-controlled (the root `.gitignore` has explicit negations
for the jniLibs tree).

At runtime the APK extracts these to the app's `nativeLibraryDir` and
`WorkerConnection.start()` spawns the binary directly via `ProcessBuilder` with
`LD_LIBRARY_PATH` and `--litert_dispatch_lib_dir` pointed there.  No `su` or
root needed for the worker process itself — this keeps execution in the app's
own SELinux/linker namespace (same approach PhoneRAG uses for EmbeddingGemma).

### External dependency: model file

The Gemma 3 1B model must be present on-device at:

```text
/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm
```

It was copied out of Termux private storage because the standalone shell runner
could not read the original app-private model path.  The model is ~1.6 GB and is
not bundled in the APK.

## 4. Phone staging (legacy smoke-test reference)

The `/data/local/tmp/poll-e-worker-test/` directory is retained for standalone
smoke-testing (outside the APK) but is no longer used at APK runtime:

```bash
adb -s 100.69.13.12:5555 shell 'mkdir -p /data/local/tmp/poll-e-worker-test'
adb -s 100.69.13.12:5555 push bazel-bin/runtime/engine/litert_lm_main \
  /data/local/tmp/poll-e-worker-test/litert_lm_main
adb -s 100.69.13.12:5555 shell \
  'chmod 755 /data/local/tmp/poll-e-worker-test/litert_lm_main'
```

NPU support libraries:

```bash
adb -s 100.69.13.12:5555 shell '
  cp /data/local/tmp/litertlm-test/libLiteRtDispatch_GoogleTensor.so \
     /data/local/tmp/poll-e-worker-test/
  cp /data/local/tmp/litertlm-test/libGemmaModelConstraintProvider.so \
     /data/local/tmp/poll-e-worker-test/
'
```

### Smoke test (standalone, outside APK)

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

Worker mode:

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

Confirmed output (2026-06-13 v2 binary, Messages context):

```text
POLL_E_READY
POLL_E_BEGIN
Want to meet at 7
POLL_E_END
```

## 5. AccessibilityService APK

Source lives in `poll-e-service/`. Build with:

```bash
cd poll-e-service
export ANDROID_HOME=/home/comrade/lib/android-sdk-9123335
./gradlew assembleDebug
adb -s 100.69.13.12:5555 install app/build/outputs/apk/debug/app-debug.apk
```

### Signing

Production installs should use the Termux family signing key so the APK
shares a trust domain with the rest of the `com.termux.*` suite.  The
signing config lives in `app/build.gradle.kts` and reads from gradle
properties (`TERMUX_KEYSTORE`, etc.).  Debug builds use the default
debug keystore.

### Phone setup

1. Settings → Accessibility → Poll-E → enable.
2. If Termux shell commands are needed in query responses, grant root
   access to `com.termux.suggest` in APatch (`/data/adb/ap/package_config`).
   The worker binary itself does NOT need root — only the `su -c` calls
   that execute `ls`/`cat`/`rg`/`find` during shell-loop rounds.
3. Monitor: `adb logcat -s PollE:D PollE/Worker:D PollE/Dispatcher:D`

### Query triggers (Mode B)

The service listens for `com.termux.suggest.QUERY` broadcasts.  In
production this is sent by Keymapper on vol-down hold.  For manual
testing:

```bash
adb -s 100.69.13.12:5555 shell 'am broadcast -a com.termux.suggest.QUERY'
```

The service reads the currently focused input field via
`findFocus(FOCUS_INPUT)`, sends that text to the resident Gemma 3 1B
worker, and runs the allowlisted shell loop (up to 10 rounds).  The
final answer is posted to both the system clipboard and a notification.

### semantic_search

The `semantic_search` pseudo-command in the shell loop calls the
Phone RAG service at `http://127.0.0.1:8791/query` (EmbeddingGemma
300M, resident on NPU).  Phone RAG must be installed and running
on the device for this to work.

### Concurrent model residency

Both Gemma 3 1B (Poll-E worker) and EmbeddingGemma 300M (Phone RAG)
are resident on the Tensor G5 NPU simultaneously.  16 GB UMA on the
Pixel 10 Pro easily holds both compiled graphs with no observed
eviction in testing (2026-06-26).
