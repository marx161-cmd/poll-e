# Runtime Artifacts - 2026-06-13

These artifacts are intentionally not committed to git.

## Final Poll-E Worker

```text
SHA256: c6f72a80fd647f74cf4d9e172f7d222251751498e24f0fd19937ca02f793af07
Local:  /home/comrade/homelab/Poll-E/runtime-backup-2026-06-13/poll-e-worker-bazel-r29/litert_lm_main
Phone:  /data/local/tmp/poll-e-worker-test/litert_lm_main
```

## Required Phone-Side Libraries

```text
SHA256: a4916c136e0651a1cbd3fe90c1c1eb4ac9e47945a301ea22cd907a4dd6c6a3b3
Phone:  /data/local/tmp/poll-e-worker-test/libLiteRtDispatch_GoogleTensor.so

SHA256: 985ec5778144730b80666a0f71f1e06038eb07dd1a3e941c6ab7f963eda00a8e
Phone:  /data/local/tmp/poll-e-worker-test/libGemmaModelConstraintProvider.so
```

## Model

```text
Phone: /data/local/tmp/gemma3-1b-it-tensor-g5.litertlm
```

The model was copied from Termux private storage into `/data/local/tmp` because
the standalone ADB shell runner cannot open the original app-private path.
