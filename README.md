# Poll-E

Poll-E is an experimental on-device ambient suggestion worker for a rooted Pixel
with Tensor G5 NPU support. The current proof point is a patched LiteRT-LM
Android runner that can load Google's Gemma 3 1B Tensor G5 package, read screen
snapshot text from stdin, and emit bounded suggestion blocks.

The repository is source-first. Large local recovery trees, model files, and
runtime binaries are intentionally not committed.

## Current State

- Device target: Pixel 10 Pro / Tensor G5 / Android 16.
- Model tested: Google Gemma 3 1B Tensor G5 LiteRT-LM package.
- Runtime path: LiteRT-LM built with Bazel and Android NDK r29.
- Worker protocol: line-based stdin snapshots; `POLL_E_READY`, then
  `POLL_E_BEGIN` / `POLL_E_END` response blocks.
- Baseline profile source: `--poll_e_profile_file=/path/to/profile.txt`.
- Current safe behavior: one loaded engine, fresh conversation/session per poll.
  This prevents history growth but re-prefills the profile baseline per poll.

## Important NPU Limitation

The Tensor G5 NPU executor currently blocks the ideal reusable-prefix KV design
through public LiteRT-LM APIs:

- `RewindToCheckpoint()` fails with:
  `NPU executor's SetCurrentStep only supports rolling back one token at the end of decode`.
- `Conversation::Clone()` fails with:
  `GetRuntimeConfig not implemented for backend: LiteRT NPU Compiled Model`.

The next runtime research target is a backend-specific way to export/import or
reuse prefix KV/cache state without relying on those public session mechanisms.

## Files

- `Poll-E-Concept.md` - product and architecture concept.
- `runtime.md` - current runtime findings, exact commands, tests, and limits.
- `source-patches/litert-lm-poll-e-worker-2026-06-13.patch` - patch to apply to
  the preserved LiteRT-LM working tree.
- `run-gemma3-1b-npu.sh`, `push-runtime-wrapper.sh`,
  `termux-poll-e-gemma3-1b-npu` - local helper scripts from the current setup.

## Tested Binary

Final tested worker binary SHA256:

```text
c6f72a80fd647f74cf4d9e172f7d222251751498e24f0fd19937ca02f793af07
```

Local backup path on the homelab machine:

```text
/home/comrade/homelab/Poll-E/runtime-backup-2026-06-13/poll-e-worker-bazel-r29/litert_lm_main
```

Phone staging path:

```text
/data/local/tmp/poll-e-worker-test/litert_lm_main
```

## Recreate

See `RECREATE.md`.
