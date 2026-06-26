# Poll-E v2: Tool-Router Architecture

**Status: supersedes `Poll-E-Concept.md` and `Poll-E-KV-Update.md`.** Those docs
describe an ambient-autocomplete product built around a daily-primed 32K KV
cache. That product is dead — confirmed bad, not just untested. This doc
replaces it. Keep the old docs for runtime/build history (worker binary,
patch, NPU executor quirks) but stop treating their *product* framing as
current.

---

## 0. What changed and why

**Killed:** Gemma 3 1B as an ambient text-field autocomplete engine.
Confirmed on-device: it is reliably bad at "guess what the user is about to
type." This is a known weight-class limitation (predicting open-ended human
intent from a screen snapshot needs world-model judgment a 1B doesn't have),
not a prompting or context-budget problem. More profile tuning will not fix
it — don't re-spend effort there.

**Kept (and load-bearing for v2):**
- Resident worker, hot-loaded model, sub-second turnaround even on a wrong
  first guess. This is the single most important asset from v1 — it's what
  makes "let the model try, fail fast, retry" viable instead of something to
  design around.
- Daily/periodic device-state snapshot pipeline (`pixelstate.sh`, trimming +
  redaction in `extract_snapshot.sh`) — still useful as an input *source* for
  some tools, just not as a giant prefix to prefill.
- AccessibilityService APK, signature-level IPC, status-bar slot via
  PixelXpert fork — all still useful as plumbing, repointed at a different
  output shape.
- Actual context budget: **4K tokens** (corrected from the earlier
  1280-token figure, which was specific to a since-replaced NPU package).
  Headroom is generous for tool-call style prompts; the system prompt now
  saturates ~1500 tokens with role, strategy, and examples.
- Precaching/priming (clone or save-restore of a primed conversation) is
  confirmed working on this NPU package in the *engine layer*, but
  `Conversation::Clone()` and `RewindToCheckpoint()` both fail at the NPU
  executor level, so true single-prefill KV reuse is blocked pending an
  upstream LiteRT-LM fix.

**New:** Gemma's job changes from *generate the right text* to *pick the
right tool, from a small fixed set, and pass it simple arguments*. That's a
classification/routing task, which is something a 1B is much more reliably
good at. Two source ideas (event-reaction, pop-up query box) plus several
more below all reduce to this same shape.

---

## 1. The two trigger modes

Mode A always uses the menu mechanism (§2b) — its triggers are narrow and
custom-action-shaped by nature. Mode B splits by request shape: lookup
queries get the shell loop, everything else gets the menu (see §2 for why).
Either way, what Gemma sees is assembled fresh per call by your code, never
a fixed global mapping.

### Mode A — Reactive (system-initiated)
Trigger: notification posted, temp/battery threshold crossed, accessibility
doorbell event, periodic snapshot diff.
- Caller pushes a small, already-known-relevant context blob (the
  notification text, the diff, the metric) — no guessing needed about what's
  relevant, since the trigger *is* the relevant thing.
- The menu built for this call is narrow — usually 1-3 real options plus
  "none" — because the trigger already narrowed the field. Gemma's job is
  classify/decide whether and how to react, optionally pick 1 tool to act
  (mute, summarize, alert, log).
- Stays push-style (context in, not tool-pull) because there's nothing to
  *discover* — the trigger already handed over the relevant data. Forcing a
  multi-step tool-call round trip here just adds latency for no benefit.

### Mode B — On-demand (user-initiated)
Trigger: user types a query into any focused text field (e.g. typed into
Gemini's own input box, never sent there — it's just a convenient
already-on-screen text field) and fires the Gemma trigger. Worker reads the
focused field directly (`findFocus(FOCUS_INPUT)`-style targeted read, not
the full-screen tree-walk used for Mode A context) so the query is captured
without surrounding screen noise.

Two sub-paths, split by request shape — see §2 for why:

- **Lookup/search-shaped queries** ("where's that script", "find the litert
  patch") → **allowlisted shell loop** (§2c). No menu, no digit-picking —
  Gemma free-generates real shell commands.
- **Everything else** (custom non-filesystem actions: check a metric,
  digest notifications, explicitly do nothing) → **menu** (§2b), same
  digit-pick mechanism as Mode A.

Given measured per-round latency (~50-300ms even at full 4K context), a
multi-round shell loop is comfortably cheap — see §2c for the actual budget.

---

## 2. Two dispatch shapes: menu vs. shell loop

Lookup-shaped requests and custom-action requests don't want the same
mechanism, so don't force them into one:

- **Custom actions** (`get_device_metric`, `notification_digest`, "ignore")
  have no existing command Gemma already knows — they're invented
  interfaces it can only learn from your few-shot examples. Digit-menu
  (§2b) keeps these single-token-reliable.
- **Lookup/search** (find a file, read a file, grep for content) maps onto
  `ls`/`cat`/`rg`/`find` — commands Gemma already has strong priors on from
  training. Asking it to route through an invented `find_script` tool
  abstraction throws away that prior for no benefit. Free shell-command
  generation, scoped by an execution allowlist (§2c), fits better here. The
  one genuinely custom verb in the allowlist is `semantic_search` (backed by
  EmbeddingGemma, see §3) — for queries that are conceptual rather than
  exact-text, where `rg`/`find` would come up empty even though the
  content's right there.

### Catalog (custom actions only — see §2c for shell binaries)

| Action | Args | Returns | Notes |
|---|---|---|---|
| `get_device_metric` | `metric: enum(battery_temp, battery_pct, uptime, free_ram)` | value | Cheap syscalls, no model needed once `metric` is chosen. |
| `read_clipboard` | — | text | For clipboard-triggered chip suggestions (call/map/track actions). |
| `notification_digest` | `window: enum(last_hour, today)` | short list | Summarizes/classifies recent notifications by the triage rules in §4, not free generation. |
| `run_snapshot_diff` | — | diff text | Wraps existing `pixelstate.sh` diff. Used by the anomaly-narrator path. |

Keep the catalog short on purpose. Add an action only after a concrete use
case needs it — the registry growing ad-hoc was part of how v1 sprawled.
`find_script`/file lookup is deliberately *not* in this catalog — that's
the shell loop's job now (§2c).

### 2b. Menu construction (per call, custom actions only)

The calling code, not Gemma, decides which catalog entries are even
candidates for a given trigger, then numbers only those:

```
Available actions:
1. Check battery temperature
2. None of these

Pick a number.
```

Rules:
- **Always include an explicit "none of these" option**, numbered last.
  Same role NONE played in the old autocomplete prompt — without it, a 1B
  forced to pick 1..N will hallucinate relevance when nothing actually fits.
- **Menu size should track trigger specificity.** Mode A (reactive) triggers
  usually justify a menu of 1-3 real options plus "none" — the trigger
  already narrowed the field.
- **Output stays single-digit** regardless of menu size (cap menus at 9 for
  this reason). A fixed global "digit N always means action X" mapping
  doesn't work since relevance is context-dependent — the menu is rebuilt
  every call, numbered 1..N for *that call only*, and the digit Gemma
  returns is an index into the menu just sent, never a stable ID.

### 2c. Shell loop (lookup/search queries)

For lookup-shaped Mode B queries:

- **Allowlisted binaries only**, enforced by argv[0] match against a fixed
  list: `ls`, `cat`, `rg`, `find`/`glob` (real binaries, exact-text), plus
  `semantic_search <query>` (the one custom verb — backed by EmbeddingGemma,
  §3 — for conceptual queries where exact-text tools miss). Custom
  non-filesystem actions can also be wrapped as fake binaries in the same
  restricted `bin/` dir later, so Gemma only ever needs one mental model —
  "run an allowlisted command" — if that turns out worth doing. No shell
  metacharacters passed through (`|`, `;`, `>`, `` ` ``, `$(...)`) — strip or
  reject on sight, since chaining reopens what the allowlist was trying to
  close.
- **Multi-round, capped at 10.** Each round: Gemma either issues another
  command or answers. Context (query + every prior command and its output)
  carries forward across rounds in the same primed conversation — this is
  exactly what the existing clone/save-restore priming already supports,
  each round is just another turn, not a cold call.
- **Round 10 is a forced answer**, not a forced command — if the cap is hit
  mid-loop, cut it off and force a synthesis-only pass ("answer with what
  you have, including 'couldn't find it' if true").
- **Per-round latency is ~50-300ms even at full 4K context**, so the
  10-round ceiling is a runaway/sanity guard, not a latency budget — worst
  case is a few seconds, not a concern at these numbers. No need to
  optimize round count further.
- **Final output is Gemma's synthesized answer, not raw command output.**
  Raw `rg`/`cat`/`ls` output is noisy; the last round's job is to read the
  accumulated trail and produce one short answer. That answer — not the
  command output — is what gets delivered (§2d).
- Deliberately not adding a verification/double-check step (re-confirming
  an answer before committing to it) right now — leave for later if
  accuracy needs it; don't add complexity speculatively.

### 2d. Delivery

Mode B's final synthesized answer (lookup loop's last-round answer, or a
custom action's result) is pushed two ways simultaneously:
- **Notification** — visible without requiring continued attention.
- **Clipboard** — immediately pasteable wherever needed next.

Open: full text in both, or truncate the notification (Android notifications
don't show much before "expand" anyway) while clipboard always gets the
full text? Leaning toward the latter — truncating clipboard would silently
drop data that was wanted.

---

## 3. Model residency

- **Gemma 3 1B** — primed/hot, handles the menu (§2b) and the shell loop
  (§2c), plus the small amount of actual text generation that's still
  appropriate (one-sentence anomaly explanations, notification digest
  summaries, Mode B's final synthesized answer — bounded, low-stakes
  generation, not "guess the user's next keystroke").
- **EmbeddingGemma** — hot, backs a `semantic_search` command in the shell
  loop's allowlist (§2c) — query-side embedding against the existing
  precomputed script DB. Covers the case `rg`/`find` miss: you remember
  *what something was about*, not the exact string it contains. `rg`/`find`
  stay the default for exact-text lookups; `semantic_search` is there for
  when those come up empty or the query is clearly conceptual rather than
  literal ("that thing about KV cache priming" vs. "litert_lm_main.cc").

Dual NPU residency is assumed fine for this device (16GB UMA, both compiled
graphs loaded, no observed eviction in casual testing). Worth a quick
benchmark pass once the loop is wired up — compare a Gemma round trip
immediately after a `semantic_search` call vs. one with no preceding
EmbeddingGemma call. If there's a real reload tax, it'll show up as a
latency outlier and can be addressed then; not worth blocking on it now.

---

## 4. Notification triage (Mode A detail)

Concrete instance of the reactive mode, since it's one of the better-shaped
use cases:

```
notification posted
  → doorbell (existing AccessibilityService / NotificationListenerService path)
  → push {app, title, text} to primed Gemma conversation
  → Gemma classifies: {important | low-value | spam-like} + optional one-line reason
  → route:
      important    → surface normally (no Poll-E intervention)
      low-value    → batch into digest, don't surface immediately
      spam-like    → suppress, log for the feedback loop
```

Same AND-gate discipline as the old output-gate design (Poll-E-Concept §7)
still applies: default to silence, surface only on a clear signal, respect
a per-period budget so this doesn't become its own nag source.

---

## 5. Anomaly narrator (Mode A detail)

```
pixelstate.sh diff (existing, already deterministic/diffable)
  → if diff non-empty:
      push diff text to primed Gemma conversation
      → Gemma: one-sentence "this changed, here's why it might matter"
  → surface via existing status-bar slot
```

This is closer to v1's strength (Gemma summarizing a small, well-structured
input) than to its weakness (Gemma guessing open-ended intent), so quality
risk here is much lower than the old autocomplete path.

---

## 6. Open questions / verify next

- [x] **Mode B trigger mechanism:** `com.termux.suggest.QUERY` broadcast,
      Keymapper vol-down hold.  Verified (2026-06-26).
- [ ] Decide notification triage source: existing AccessibilityService doorbell
      vs. a proper `NotificationListenerService` (cleaner API for this
      specific job, separate permission).
- [x] **Shell loop allowlist:** `ls` `cat` `rg` `find` + `semantic_search`.
      Command sanitizer strips `;&|`\$()><\n#"`; args with `\\`, `*`, `?`, etc.
      are single-quoted before execution.  Verified (2026-06-26).
- [x] **Delivery:** full text to clipboard always; notification shows title +
      big-text body.  Implemented (2026-06-26).
- [x] **`semantic_search`:** query-side HTTP call to Phone RAG at
      `http://127.0.0.1:8791/query`.  Latency < 100ms on-device.  Phone RAG must
      be installed and running.
- [x] **Worker packaging:** binary + dispatch libs bundled as jniLibs in the APK;
      no `su` needed for model inference.  Same approach as Phone RAG.
      Verified (2026-06-26).
- [ ] Feedback loop (accept/dismiss → loosen/tighten) from the old design
      (Poll-E-Concept §7) still applies to results surfaced to the user —
      re-wire it against menu actions and shell-loop answers rather than
      autocomplete suggestions.

---

## 7. Explicitly not doing

- Not resurrecting ambient text-field autocomplete. The old `poll-e-profile.txt`
  / `profile_source.txt` / `profile_recap.txt` few-shot files are retired —
  keep them in repo history for reference, not as live config.
- Not chasing a bigger model to fix autocomplete quality. The fix is
  changing the task shape, not the model size.
- Not letting the shell loop (§2c) or the custom-action catalog (§2) grow
  unbounded. The loop is intentionally capped at 10 rounds and scoped to a
  fixed binary allowlist; the catalog is intentionally small. "Agentic" here
  means a bounded, allowlisted loop with a forced-answer ceiling — not an
  open-ended agent free to call anything, indefinitely.
- Not adding a verification/double-check pass to the shell loop speculatively
  (§2c) — only if accuracy in practice turns out to need it.
