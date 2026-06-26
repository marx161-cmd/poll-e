# Ambient Suggestion Daemon — Project Layout

> On-device, always-on contextual suggestion system for a rooted Pixel 10 Pro.
> Resident small Gemma predicts fitting content from live screen context; suggestions
> surface in a custom status-bar slot and are confirmed with volume keys.
> Fully local — no network, privacy is moot because nothing leaves the device.
>
> Slots into the existing Termux/Kai family (`com.termux.*`, single signing key).

---

## 0. One-line concept

A resident model runs as a continuous **predictor** of "what fits here"; the output is a **tap**.
Predict eagerly and internally; surface only when the gate opens. Decode constantly, reveal on demand.

---

## 1. Platform & target

- **Device:** Pixel 10 Pro, Android 16, rooted (APatch + LSPosed).
- **Silicon:** Tensor G5, on-device TPU.
- **Existing proof-in-family:** Kai (`com.termux.kai`, `2.5.1-termux`) — already rebuilt, re-signed, and living in the Termux universe. Existence proof for the whole packaging model.

---

## 2. Model

- **Primary:** Gemma 3 1B — **text-only** (no vision encoder; that starts at 4B/12B/27B via SigLIP).
- If scene-level vision is ever wanted on the same TPU path: Gemma **3n E2B/E4B** or **Gemma 4 E2B/E4B** (multimodal, NPU-capable via LiteRT-LM `visionBackend`).
- **Do NOT use a Gemma VLM as an OCR engine** — it compresses the frame to 256 soft tokens @ 896×896, which is fine for "what is this screen about" but character-inexact. Exact strings (URLs, paths) need a real OCR lib.

### Runtime facts that shaped the design
- **LiteRT-LM CLI** = CPU/GPU backends out of the box. **TPU** needs the `google_tensor` dispatch lib built `android_arm64` and pushed to `/data/local/tmp`, **or** the Kotlin `Backend.NPU(...)` path in an APK. (Already handled for Kai.)
- TPU path = fast + low power. GPU path (ML Drift) = the warmer, higher-draw one — the only place the old "thermal" worry has any teeth.
- **v0.13 CLI ships an OpenAI-compatible server** → the daemon can expose a localhost endpoint and everything else just speaks that protocol.
- **Decode is memory-bandwidth bound.** Input is cheap (1B prefill ≈ 2585 tok/s — feed the whole screen, it's basically free); **output is the bill** → constrain output hard to terse, structured suggestions.
- First-token latency < 500 ms for small models on NPU; ~200 ms effective in practice. The staleness window is exactly **one inference long**.

---

## 3. Architecture (final topology)

Three apps, **one signing key, one trust domain.** Same-key = `signature`-level permissions flow with zero prompts. (Note: `sharedUserId` is dead on A16 — this is shared *trust*, not a shared process/UID. Separate processes, private signature-gated IPC.)

| Component | Package (example) | Lives in | Role |
|---|---|---|---|
| **LiteRT-LM daemon** | `com.termux.*` | Termux | Resident TPU server, boot-launched + wakelocked, localhost OpenAI-compatible endpoint |
| **Suggestion service** | `com.termux.suggest` | Real APK | Accessibility read + predict trigger + output gate + key arming + commit |
| **PixelXpert fork** | `com.termux.*` (your fork) | App process + SystemUI hook | Reads the locked suggestion channel, relays to its SystemUI hook, renders the status-bar slot |

**Why the suggestion service must be a real APK:** AccessibilityService is manifest-declared; it can't be a bare Termux process. Sign it with your key → it joins the family and talks to the daemon over localhost.

**Why signing PixelXpert matters:** its *app process* (your key) becomes the legitimate reader of the signature-locked channel, then hands the value to its own SystemUI hook via the module's internal app↔hook bridge. That collapses the only "system-open hop" — the whole cohort stays your-key-only, display path included.

---

## 4. Sensor layer (context-in)

Three complementary sources, not competing — each does what it's good at.

1. **AccessibilityService node tree — PRIMARY.**
   - `getRootInActiveWindow()` → walk `AccessibilityNodeInfo`, flatten subtree to text **with node-type labels preserved** (editable / link / heading / address-bar).
   - It's a **pull / snapshot on trigger**, NOT the event stream. The page already exists as structured, exact, labeled text — no OCR re-derivation.
   - Cost = binder IPC, scales with **tree depth**, not frequency. Scope to the focused subtree/window, don't walk 4000 nodes every time.

2. **Accessibility event stream — DOORBELL ONLY.**
   - Subscribe narrowly: `TYPE_WINDOW_STATE_CHANGED`, maybe `TYPE_VIEW_FOCUSED`. Ignore the rest.
   - Near-free to receive/filter (microseconds). Used purely to decide *when* a tree-walk is justified. Intake outruns any firehose by orders of magnitude — it was never the bottleneck.

3. **IME / InputConnection — for actively-typed text (optional path).**
   - Hook point proven by KeyboardGPT (`InputMethodService` boundary, keyboard-agnostic, up to A16). `getTextBeforeCursor`/`getTextAfterCursor` give the live sentence.
   - Decided **against** keyboard-centric *output* (too narrow vs. status-bar slot), but the hook remains valid as an input sensor if wanted.

4. **OCR (ML Kit Text Recognition) — FALLBACK ONLY.**
   - For pixel-only surfaces with no accessibility tree: canvas UIs, some game engines, DRM video.
   - On-device, fast, exact strings + bounding boxes. ~10% case, not the main act.

### Gating before the model wakes
- **Debounce / settle (~300 ms):** coalesce event bursts (page loads fire several) → walk once after stable.
- **Dedup:** hash the flattened text; identical to last snapshot → skip, don't wake the model.

---

## 5. Prediction model

- Resident model, **warm TPU context**, decode **eagerly on context change** (not on a clock — re-predicting unchanged context just reproduces the same guess).
- Hold the current prediction tagged with a **context-hash**.
- **Two independent error axes — keep them separate:**
  - **Staleness** ("is this about the current screen?") — killable. Hash + speed drive it to ~zero. At ~200 ms the stale window is a fifth of a second, narrower than human reaction to "I want it now."
  - **Wrongness** ("is this any good?") — **intrinsic to a 1B parrot, unfixable at the source.** Fresh garbage is still garbage.
- Mental model: **a loud, confident, wrong machine with a good bouncer.** The whole UX quality lives in the bouncer (§7), not the model.

---

## 6. Output / display layer

- **Surface:** custom status-bar slot in the **forked PixelXpert** (already running, hooks SystemUI, injects a managed view on A16, sits in the reclaimed notification-marker area).
- **Transport:** suggestion **published as a readable value** in the network-speed idiom — but unlike `TrafficStats` (in-process to SystemUI), this value must live where both processes reach it.
  - **Preferred:** content provider backed by the daemon/service, with change-notification so the fork re-renders on push (network-speed semantics: live without busy-polling).
  - **Alt:** world/root-readable file or pref the fork tails (cruder, poll-based, fine at this update rate).
- **Frequency budget:** netspeed already sustains ~1 Hz `setText`; gated suggestions fire a handful per *minute* — miles under proven envelope.
- Environment-agnostic and keyboard-independent — works where there's no candidate strip (URLs, paths, no-keyboard fields).

---

## 7. Output gate (the bouncer) — wrongness management

The output trigger is **stricter than the input trigger** and decoupled from it. Compute eagerly (cheap); **surfacing** is the expensive, trust-spending act. Default state = **silence**; speaking is earned.

A suggestion surfaces only if **ALL** hold (AND-gate):
- there's an **actionable target** in the current context,
- it clears a **confidence bar**, AND is **different / better** than what's already there,
- the UI is in a **settled, non-busy** state,
- it isn't a **repeat** of what was just shown.

Plus:
- **Validate-before-surface:** does that path resolve? is that URL well-formed?
- **Surface budget:** ≤ 1 pushed suggestion per N seconds unless the user engaged the last one (caps nagging even when gates pass).
- **Feedback loop:** repeated dismiss of a type → suppress that type harder; accept → loosen it.

> Wrongness can't be eliminated, only filtered. Bouncer ruthlessness = product quality.

---

## 8. Accept mechanism

- **Volume keys, conditionally armed** — only while a suggestion is live (arming window ~3–4 s). Outside the window they're just volume.
  - **Vol-up = accept**, **Vol-down = dismiss.** Both free, physical, screen-agnostic. Reject is as cheap as accept (rule: make *yes* nearly free, never make *no* expensive).
- **Visible "armed" cue** in the slot so the user always knows whether a press goes to suggestion or to volume.
- **Accept dispatches by context** (same gesture, routed verb):
  - focused editable field → `ACTION_SET_TEXT` (replaces whole field → **read-splice-write** to insert at cursor),
  - URL → fire intent,
  - path → clipboard.
- **Freshness check on accept:** compare prediction's context-hash to the screen *now*. Match → commit instantly (normal case). Mismatch (opened mid-change) → refuse stale, re-snap + re-decode (~one inference tax). Freshness over latency, always.

---

## 9. End-to-end data flow

```
context change
  → doorbell event (WINDOW_STATE_CHANGED / VIEW_FOCUSED)
  → debounce / settle (~300ms)
  → accessibility tree-walk snapshot (scoped subtree)
  → flatten to text + node-type labels
  → dedup hash  ── identical? ──> skip
  → feed daemon (localhost, OpenAI-compatible)
  → eager decode (warm TPU)
  → hold prediction + context-hash
  → OUTPUT GATE (AND-gate + validate + budget)
        └─ pass? ── no ──> stay silent
                  ── yes ─> publish suggestion to locked channel
  → PixelXpert fork reads channel
  → render status-bar slot + arm vol-keys + show armed cue
  → user:  vol-up (accept) | vol-down (dismiss) | timeout (disarm)
  → on ACCEPT:
        freshness-check hash vs current screen
          match  → commit by context-route (SET_TEXT / intent / clipboard)
          stale  → re-snap + re-decode, then commit
  → update state value → PixelXpert clears slot
```

---

## 10. Honest edges & risks

- **Password fields & WebViews** — secure-flag (`isPassword` / `FLAG_SECURE`) and uncooperative pages block both *read* and *write*. The hardest targets. **Shelve passwords as v2/maybe-never**, not a launch promise.
- **Secure screens (PIN entry, DRM)** suppress overlays *and* accessibility writes — same OS boundary on both axes. Works everywhere except where Android deliberately walls everyone out.
- **`ACTION_SET_TEXT` replaces the whole field** → always read-splice-write for cursor insert.
- **PixelXpert hook runs in SystemUI's process** → a bad crash can soft-bork SystemUI until restart. Keep the hook a dumb display; zero logic.
- **Sustained-generation thermal taper** — a slow degrade only after long pinned use, not a meltdown. Benchmark the all-day daemon, not the 5-second demo.
- **Wrongness is permanent** — managed at the gate, never solved at the model.

---

## 11. Build order (next steps)

1. **Daemon** — LiteRT-LM resident server on the **TPU** in Termux, localhost endpoint. Confirm it's actually hitting the TPU, not a silent GPU fallback. (Mostly proven via Kai.)
2. **Suggestion service skeleton** — AccessibilityService: tree-walk → flatten → dedup/debounce → call daemon. One class, three jobs (read / arm / write) + an `isArmed` boolean.
3. **Output gate** — the bouncer (§7).
4. **PixelXpert fork** — add the suggestion slot reading the published channel; arming cue.
5. **Key arming + commit routing** — vol-up/down handling in the service, context-dispatched commit.
6. **Feedback loop + budget tuning** — turn "technically correct suggester" into "thing you leave on."

---

## 12. Context window & KV cache memory

### Context budget
- **Gemma 3 1B context window: 32K tokens ≈ ~24,000 words.** Not a constraint — effectively a short novella of personal context available on every inference.
- Larger Gemma variants (4B/12B/27B) get **128K tokens** but almost certainly won't fit the TPU budget. Stay on 1B; the window is already overkill for this use case.
- Realistic per-inference partition:

  | Slot | Tokens (approx) |
  |---|---|
  | Permanent profile prefix (KV cached) | 1,000 – 3,000 |
  | Current screen snapshot | 500 – 2,000 |
  | Output (terse suggestion) | ~20 |
  | Headroom | enormous |

- **Don't try to fill the window.** A tight, curated 1–2K profile outperforms a bloated 20K one — attention dilutes over noise. Grow it deliberately when you notice a gap.

### KV cache = "permanent live context" for free

- The permanent profile prefix is **prefilled once at daemon boot** and its KV attention states cached in TPU memory.
- Every subsequent inference appends only the fresh screen snapshot (~500–2K tokens) as a delta — the profile contributes zero additional compute per query.
- **Cost model: pay ~8 seconds of prefill once at midnight, get free personal context for 24 hours.** Invisible — happens while asleep.

### What lives in the permanent profile
Curated, high-signal personal context that changes the quality of suggestions:
- SSH hostnames (`comrade`, `genosse`, `comintern`) and their roles
- Common filesystem paths (`~/homelab`, `/data/adb/`, `/sdcard/Documents/`, etc.)
- Termux key macros and their meanings (from `termux.properties` extra-keys)
- Installed modules and their scope (from LSPosed domain)
- su-granted packages (from su_grants domain)
- Personal vocabulary, abbreviations, domain-specific terms
- Accept/reject history summary (from feedback loop — §7)

### Security posture of the KV cache
- After prefill, the source profile text is **discarded**. What persists in TPU memory is floating-point KV attention tensors — not text, not token IDs.
- Casual snooping, log reads, memory dumps → hit a wall of high-dimensional floats. Nothing to grep.
- Theoretical inversion requires: physical device possession + model weights + exact quantization/layer config + significant compute. Not a realistic attack vector.
- **Threat model: "if my phone is gone I change the passwords."** The KV representation is meaningfully safer than a plaintext dotfile on disk. Not crypto-grade unrecoverable, but practically safe for the actual threat model.
- Profile source file: keep encrypted at rest (decrypt → prefill → discard source). Disk lifetime of the plaintext = milliseconds at boot.

---

## 13. Daily snapshot as profile source (`pixelstate.sh`)

### What it is
`pixelstate.sh` is a **deterministic, diffable, normalized state capture** for the Pixel. Three design properties that make it ideal as a model profile source:

1. **DETERMINISTIC** — same system state → byte-identical output (sorted, timestamps stripped, volatile quarantined). Same state = same tokens = same KV states. If nothing changed, yesterday's cache is still valid.
2. **CANONICAL** — one capture promoted as baseline; subsequent runs diff against it. The diff output is the *minimal delta* — might be 50 tokens on a quiet day.
3. **NORMALIZED** — per-domain files (settings, packages, modules, su grants, selinux, mounts, LSPosed scope, AVB). Drift localizes instantly to the affected domain.

### Domains captured (diffable, stable)
- `build` — fingerprint, security patch, verified boot state
- `selinux` — enforcing/permissive
- `kernel` — release + arch
- `settings_global/secure/system` — Android settings (sorted)
- `device_config` — Google server-pushed flags (big drift source on A16)
- `pkgs_versions` — all packages + versionCodes (catches silent updates)
- `pkgs_disabled/system/thirdpty/enabled` — package state
- `appops` — permission grants (SYSTEM_ALERT_WINDOW, RUN_IN_BACKGROUND, etc.)
- `overlays` — RRO theming / overlay enablement
- `accessibility` — registered accessibility services ← **your own service appears here**
- `device_admins` — device policy owners ← **Dhizuku appears here**
- `roles` — default apps (browser, SMS, dialer, home)
- `net` — interfaces + routes (stable identity, not stats)
- `su_grants` — which apps have root without prompt ← **most security-relevant**
- `modules` — APatch modules + enabled state
- `lsposed` — module→app scope table ← **your full hook surface**
- `lsmod` — loaded kernel modules
- `mounts` — mount table (bind-mount tampering detection)
- `verity` / `avb` — dm-verity + AVB chain

### Volatile (captured separately, NOT diffed)
Battery, uptime, memory, thermals — in `volatile.txt`. Explicitly excluded from diff and from profile prefix (would invalidate KV cache daily for no signal gain).

### Daily rotation pattern
```
00:00:01  →  pixelstate.sh capture
             pixelstate.sh diff        # alert if drift detected
             decrypt profile source
             daemon re-prefill (full snapshot → KV cache)
             discard plaintext source
```
Cron or Tasker at midnight. 8 seconds of prefill while asleep. Daemon warm with fresh profile for the entire day. Free.

### What to add (not yet in script)
- **Termux environment:** `$PATH`, `$PREFIX`, `pkg list-installed`, `~/bin` script inventory — highest-value completion signal for daily use, currently absent.
- **Termux extra-keys** already captured indirectly via the snapshot output but worth extracting explicitly as a named domain. The current extra-keys config already contains SSH host aliases (`comrade@comrade`, `genosse@comintern`) and key macros — exactly the kind of personal vocabulary that makes suggestions contextually correct.
- **Active Termux sessions / recent commands** — `~/.bash_history` tail as a volatile-side capture (don't diff, do inject into profile).

### The script's design properties work for the model, not just for diffing
- `scrub()` strips timestamps and volatile substrings → same system = same tokens = **same KV states**. The determinism was designed for diffing but accidentally makes KV reuse trivial.
- `volatile.txt` quarantine is exactly right for the profile use case — battery/uptime would invalidate the cache every single day for zero signal gain. Already solved by design.


---

## 14. Naming / family notes

### The Termux family model
Same-key signing is not a proposal — it's already proven. Kai (`com.termux.kai`, `v2.5.1-termux`, `no battery use since last full charge`) is already rebuilt, re-signed, and living in the family. The new pieces just repeat that build step:

| App | Package | Notes |
|---|---|---|
| LiteRT-LM daemon | `com.termux.lm` (or runs bare in Termux) | Already proven via Kai's TPU path |
| Suggestion service | `com.termux.suggest` | New APK, signed same key |
| PixelXpert fork | your existing fork, re-signed | Already customized, already running |

- Same signing key → `signature` protectionLevel → private API surface between all three, zero permission prompts.
- `sharedUserId` is dead on A16 — this is shared *trust*, not shared process/UID. Each component lives in its own process; the key is the trust boundary.
- **Why signing PixelXpert specifically:** its *app process* becomes the legitimate reader of the signature-locked suggestion channel. It relays the value to its own SystemUI hook via the module's internal app↔hook bridge. The hook never has to present your identity to anyone — it's the module talking to itself. The whole cohort is your-key-only, display path included, no exceptions.
- PixelXpert suggestion slot = second managed view alongside the existing netspeed view. Same `setText`-on-timer pattern, different data source. The hard part (injecting a live-updating view into SystemUI on A16) is already running on your phone daily.

### Multi-LLM role split (unchanged)
Claude = architecture/reasoning. DeepSeek = code execution (parallel sessions). Gemini = Pixel/Android hardware questions.
