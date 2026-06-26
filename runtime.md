## Build Note

Do not use the standalone CMake recipe as the primary Poll-E runtime path. The
working Android runner was built with Bazel, which preserved the LiteRT-LM link
shape needed for the Tensor G5 dispatch path. CMake remains useful only for
source exploration or isolated experiments.

### 1. Check build environment in ~/ or /mnt/DDRXT4 for existing cross compile tooling 
### 2. Clone and Cross-Compile
Now, clone the repo and point CMake to the Android toolchain file inside the NDK you just downloaded. This tells your AMD processor to compile the code specifically for an arm64-v8a Android target.
```bash
git clone https://github.com/google-ai-edge/LiteRT-LM.git
cd LiteRT-LM
mkdir build && cd build

# Configure the cross-compilation
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-33 \
  -DLITERT_LM_BUILD_CLI=ON

# Build the binary
cmake --build . --target litert_lm_main -j$(nproc)

```
### 3. Push to the Phone
Once it finishes compiling, you'll have a native litert_lm_main binary sitting in your build directory. Connect your phone via USB (or use adb over Wi-Fi if you have it configured on your rooted setup) and push it directly into the execution path:
```bash
# Push the compiled binary to the phone's temp directory
adb push litert_lm_main /data/local/tmp/

# Drop into the phone's shell to set permissions
adb shell
su
chmod +x /data/local/tmp/litert_lm_main

```

## 2026-06-13 Runtime Finding

The original Google Gemma 3 1B Tensor G5 package loads successfully on the Pixel
through the official packaged LiteRT-LM runner:

```bash
LD_LIBRARY_PATH=/data/local/tmp/litertlm-test \
  /data/local/tmp/litertlm-test/litert_lm_main \
  --backend=npu \
  --model_path=/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm \
  --input_prompt="What is 2+2?"
```

Result:

```text
SouthBound context created.
Found GoogleTensorOptions
Replacing ... with delegate (DispatchDelegate)
input_prompt: What is 2+2?
2 + 2 = 4
Time to first token: 0.26 s
Prefill Speed: 98.09 tokens/sec
Decode Speed: 10.65 tokens/sec
```

The model had to be copied out of Termux app-private storage first because the
standalone shell runner cannot open this path:

```text
/data/data/com.termux/files/home/litert_models/gemma3-1b-it-tensor-g5/model.litertlm
```

Working copy:

```text
/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm
```

The larger patched runner at `/data/local/tmp/litert_lm_main` currently crashes
after loading `libLiteRtDispatch_GoogleTensor.so`:

```text
signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x2
backtrace:
  #00 pc 0000000000000002 <unknown>
  #01 pc 000000000001b064 /data/local/tmp/litertlm-test/libLiteRtDispatch_GoogleTensor.so
```

So the current Poll-E runtime baseline is the official packaged runner, not the
patched 235 MB runner.

Local helper:

```bash
./run-gemma3-1b-npu.sh "What is 2+2?"
```

Phone-side helper:

```bash
./push-runtime-wrapper.sh
adb -s 100.69.13.12:5555 shell /data/local/tmp/poll-e-gemma3-1b-npu "What is 2+2?"
```

Backup of the working runner, dispatch support library, constraint provider, and
wrapper lives in:

```text
/home/comrade/homelab/Poll-E/runtime-backup-2026-06-13/
```

## 2026-06-26 Poll-E v2 Query Dispatcher + jniLibs Worker

The worker binary and NPU dispatch libraries (`libLiteRtDispatch_GoogleTensor.so`,
`libGemmaModelConstraintProvider.so`) are now packaged as jniLibs in the APK
(`app/src/main/jniLibs/arm64-v8a/`).  At runtime, `WorkerConnection.start()`
spawns the binary from the app's `nativeLibraryDir` with
`--litert_dispatch_lib_dir` pointed there.  No `su` or root needed for the
worker — execution stays in the app's own SELinux/linker namespace.

This mirrors Phone RAG's approach for EmbeddingGemma and solves the linker
symbol error (`cannot locate symbol PermissionCache::checkPermission` in
`libgui.so`) that occurred when the binary was spawned via `su`.

### Shell loop for shell commands

The QueryDispatcher's `runAllowlistedCommand()` still uses `su -c` for executing
`ls`/`cat`/`rg`/`find` commands during query rounds (needed to reach Termux
binaries at `/data/data/com.termux/files/usr/bin/`).  The APK must be in
APatch's `package_config` allowlist.

### QueryDispatcher fixes (2026-06-26)

- `worker.request()` now has a 15-second `withTimeout` guard.
- Command sanitizer no longer strips backslashes; args with shell-special
  characters are single-quoted before shell execution.
- `ANSWER:` prefix parsing is case-insensitive and tolerates extra whitespace.
- `semantic_search` pseudo-command calls Phone RAG at `http://127.0.0.1:8791/query`.
- `/data/local/tmp/poll-e-worker-test/` smoke tests still work, but the APK path
  is the authoritative runtime path now.

### Verified on Pixel (2026-06-26)

```text
Poll-E query: "where is the litert binary"
→ 10-round shell loop completed
→ Answer delivered to clipboard + notification
→ Per-round latency: ~2000–3200 ms (cold prefill per round with fresh
  Conversation; KV reuse via Clone available in current build — see below)
→ Gemma 3 1B + EmbeddingGemma 300M both resident on NPU simultaneously
```

Patched source tree:

```text
/home/comrade/homelab/Poll-E/recovery-2026-06-13/LiteRT-LM-working-copy
```

Build command that succeeded:

```bash
export ANDROID_NDK_HOME=/home/comrade/lib/android-ndk-r29
bazelisk build --config=android_arm64 //runtime/engine:litert_lm_main
```

NDK r26d failed inside the Rust `cxx` crate/libc++ compile path. NDK r29 built
successfully.

Patch behavior:

- `--poll_e_worker` keeps the model resident and reads one screen snapshot per
  stdin line.
- `--poll_e_profile_file=/path/to/profile.txt` loads a static system preface and
  asks LiteRT-LM to prefill it. This file is the daily-refresh baseline input:
  regenerate it, restart the worker, and the next worker process will use the
  new baseline.
- `--poll_e_max_output_tokens=N` caps each suggestion.
- Worker protocol is `POLL_E_READY`, then `POLL_E_BEGIN` / `POLL_E_END` blocks.
  Native dispatch may print one startup line before `POLL_E_READY`; callers
  should ignore output until ready.
- Uses `Conversation::Clone()` to fork the primed conversation per poll:
  profile is prefilled once at startup; each poll clones the primed
  conversation (KV cache included), appends the snapshot as a user turn,
  decodes, then discards the clone.  Single prefill cost per daemon lifetime.

Staged phone copy:

```text
/data/local/tmp/poll-e-worker-test/litert_lm_main
```

Local backup:

```text
/home/comrade/homelab/Poll-E/runtime-backup-2026-06-13/poll-e-worker-bazel-r29/litert_lm_main
```

SHA256 (v2, direct-completion prompt fix, 2026-06-13):

```text
39c7598c386dba024248dc15a87325584de435ec79505306575e57adb86b765c
```

Smoke tests on the Pixel succeeded:

```bash
cd /data/local/tmp/poll-e-worker-test
LD_LIBRARY_PATH=/data/local/tmp/poll-e-worker-test ./litert_lm_main \
  --backend=npu \
  --model_path=/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm \
  --input_prompt="What is 2+2?"
```

Result:

```text
SouthBound symbols resolved by 'libedgetpu_litert.so'
2 + 2 = 4
Time to first token: 0.23 s
Decode Speed: 11.55 tokens/sec
```

Resident worker smoke (v2 — generates actual suggestions):

```bash
adb -s 100.69.13.12:5555 shell 'cd /data/local/tmp/poll-e-worker-test && \
  printf "%s\n%s\n" \
    "App: Messages. Focused input contains: hello. Recent chat asks: Want to meet at 7?" \
    "__quit__" | \
  LD_LIBRARY_PATH=/data/local/tmp/poll-e-worker-test ./litert_lm_main \
    --backend=npu \
    --model_path=/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm \
    --poll_e_worker \
    --poll_e_max_output_tokens=24'
```

Observed output (2026-06-13 v2):

```text
POLL_E_READY
POLL_E_BEGIN
Want to meet at 7
POLL_E_END
```

### Prompt engineering note

The original prompt used complex role-playing gate instructions
("You are Poll-E... if no insertion would be useful, reply exactly NONE")
which caused Gemma 3 1B to return NONE conservatively for all inputs.

The fix changes `BuildPollEPrompt` to a direct completion frame:

```
Phone screen snapshot:
{snapshot}

Based on the snapshot above, complete the text in the focused
input field. Reply with only the text to type, no quotes, no
markdown, no explanation. Keep it short and natural. If no text
field is visible or focused, reply NONE.
```

This produces natural completions for Messages/Gmail contexts and
correctly returns NONE for screens with no focused text field.
