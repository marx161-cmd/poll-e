# Poll-E

Poll-E is an experimental on-device ambient suggestion daemon for a rooted Pixel
10 Pro (Tensor G5). It consists of three components that share a signing key:

| Component | Status | Package |
|-----------|--------|---------|
| LiteRT-LM worker | **Working** | native binary, `com.termux.*` Termux |
| AccessibilityService APK | **Installed prototype** | `com.termux.suggest` |
| PixelXpert fork with slot | **Installed prototype** | `sh.siava.pixelxpert` |

The repository is source-first. Large local recovery trees, model files, and
runtime binaries are intentionally not committed.

## Current State (2026-06-13)

- **Device target:** Pixel 10 Pro / Tensor G5 / Android 16.
- **Model tested:** Google Gemma 3 1B Tensor G5 LiteRT-LM package.
- **Runtime:** LiteRT-LM built with Bazel and Android NDK r29, running on NPU.
- **Worker confirmed generating suggestions:** `POLL_E_BEGIN / Want to meet at 7 / POLL_E_END`
  for a Messages context. Prompt: direct completion framing (not role-playing).
- **AccessibilityService APK prototype:** installed and enabled; wires
  accessibility tree walk → debounce/dedup → single-flight worker IPC →
  signature-protected broadcast.
- **PixelXpert slot prototype:** installed; receives signed Poll-E broadcasts
  and displays the suggestion in the status bar. Accept/insert is still TODO.

## Architecture

```
context change
  → AccessibilityEvent (doorbell: WINDOW_STATE_CHANGED / VIEW_FOCUSED)
  → debounce 300 ms
  → accessibility tree-walk → flatten to snapshot string
  → dedup hash  ── identical? ──> skip
  → single-flight request gate  ── busy? ──> keep latest snapshot only
  → litert_lm_main (stdin/stdout via su, NPU)
  → POLL_E_BEGIN / suggestion text / POLL_E_END
  → output gate (NONE? skip. blank? skip.)
  → ACTION_SUGGESTION broadcast with signature permission
  → PixelXpert status-bar slot
  → vol-up: accept / vol-down: dismiss  [TODO]
```

Measured resident-worker request latency on the Pixel:

- low context, ~65 chars: `0.40-0.55s`
- mid context, ~2k chars: `1.31-1.49s`
- high valid context, ~5k chars: `1.65-1.68s`

The Gemma 3 1B Tensor G5 package has a 1280-token context limit. The APK caps
snapshots at 5000 chars before writing to the worker to avoid killing the native
process with an overlong request.

## Worker Protocol

The native binary speaks a line-based stdin/stdout protocol:

```
POLL_E_READY           ← one-time on startup (after model loads, ~2 s on NPU)
<snapshot line>\n      → stdin from APK
POLL_E_BEGIN           ← start of response
<suggestion text>      ← actual text, or NONE if no text field visible
POLL_E_END             ← end of response
```

Ignore all stdout before `POLL_E_READY`; the native dispatch lib can print
startup noise on the same line.

## Worker Binary

Built with Bazel + NDK r29 from the LiteRT-LM working copy.

SHA256 (v2, direct-completion prompt, 2026-06-13):

```
39c7598c386dba024248dc15a87325584de435ec79505306575e57adb86b765c
```

Local backup: `runtime-backup-2026-06-13/poll-e-worker-bazel-r29/litert_lm_main`  
Phone path: `/data/local/tmp/poll-e-worker-test/litert_lm_main`

## NPU Limitations

The Tensor G5 NPU executor blocks the ideal reusable-prefix KV design via public
LiteRT-LM APIs:

- `RewindToCheckpoint()` → `NPU executor's SetCurrentStep only supports rolling
  back one token at the end of decode`.
- `Conversation::Clone()` → `GetRuntimeConfig not implemented for backend: LiteRT
  NPU Compiled Model`.

Workaround: one loaded engine, fresh conversation per poll. Re-prefills the
profile baseline each poll; prevents history growth.

## Files

- `Poll-E-Concept.md` — full product and architecture concept.
- `runtime.md` — runtime findings, exact commands, performance numbers, limits.
- `RECREATE.md` — step-by-step build and staging guide.
- `source-patches/litert-lm-poll-e-worker-2026-06-13.patch` — patch for
  `runtime/engine/litert_lm_main.cc` and the NPU stub guard.
- `poll-e-service/` — AccessibilityService APK source (Kotlin, AGP 8.7, API 36).

## Building the APK

```bash
cd poll-e-service
# Set ANDROID_HOME or let local.properties point to the SDK.
export ANDROID_HOME=/home/comrade/lib/android-sdk-9123335
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is unsigned with the debug keystore. For production, sign it with
the same key used for the rest of the Termux family so `signature`-level IPC
works across components.

**Root access required:** grant `com.termux.suggest` root in APatch settings
before enabling the accessibility service.

## Recreate Worker

See `RECREATE.md`.
