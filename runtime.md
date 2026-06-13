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

## 2026-06-13 Poll-E Resident Worker Patch

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
- The current Tensor G5 NPU executor does not support true reusable-prefix KV
  restoration through public LiteRT-LM APIs. `RewindToCheckpoint()` fails with
  `NPU executor's SetCurrentStep only supports rolling back one token at the end
  of decode`, and `Conversation::Clone()` fails with `GetRuntimeConfig not
  implemented for backend: LiteRT NPU Compiled Model`.
- The working fallback keeps the engine/model loaded but creates a fresh
  conversation/session for each poll. That prevents history growth and keeps
  behavior correct, but it re-prefills the profile baseline per poll.

Staged phone copy:

```text
/data/local/tmp/poll-e-worker-test/litert_lm_main
```

Local backup:

```text
/home/comrade/homelab/Poll-E/runtime-backup-2026-06-13/poll-e-worker-bazel-r29/litert_lm_main
```

SHA256:

```text
c6f72a80fd647f74cf4d9e172f7d222251751498e24f0fd19937ca02f793af07
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

Resident worker smoke:

```bash
printf "%s\n%s\n" \
  "App: Messages. Focused input contains: hello. Recent chat asks: Want to meet at 7?" \
  "__quit__" |
LD_LIBRARY_PATH=/data/local/tmp/poll-e-worker-test \
  /data/local/tmp/poll-e-worker-test/litert_lm_main \
  --backend=npu \
  --model_path=/data/local/tmp/gemma3-1b-it-tensor-g5.litertlm \
  --poll_e_worker \
  --poll_e_max_output_tokens=24
```

Observed protocol:

```text
POLL_E_READY
POLL_E_BEGIN
NONE
POLL_E_END
```

Final multi-request smoke:

```text
POLL_E_READY
POLL_E_BEGIN
NONE
POLL_E_END
POLL_E_BEGIN
NONE
POLL_E_END
```
