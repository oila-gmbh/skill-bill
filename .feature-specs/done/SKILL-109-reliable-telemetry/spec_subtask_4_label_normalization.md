---
issue_key: SKILL-109
subtask_id: 4
status: Complete
---

# Subtask 4 — Normalize platform/stack labels + `routed_skill` prefix

## Scope

Replace free-text platform/stack labels with enum slugs plus an explicit fallback flag, and stop
the `skill-bill:` namespace prefix and blank routing fields from reaching telemetry.

## Root cause

`review_platform` is set to `reviewSummary.detectedStack` verbatim — the caller's free-text stack
fingerprint (24 variants for 10 real platforms) — while `platform_slug`
(`ReviewFinishedPayloadSupport.kt:122`) is already the normalized enum. `detected_stack` embeds
fallback prose (e.g. `"kmp -> kotlin quality-check fallback"`). `routed_skill` is read straight from
MCP args (`McpLifecycleToolHandlers.kt:106`) and can carry a `skill-bill:` namespace prefix or be
blank.

## Proposed solution

- Emit `review_platform`/`detected_stack` as the clean slug already produced by
  `ParallelCodeReviewRunner.detectStack()` (`:158`); move any descriptive fingerprint into a
  separate field (e.g. `detected_stack_detail`). Normalize at `ReviewFinishedPayloadSupport.kt:33`
  so `review_platform` equals `platform_slug`.
- Replace prose-in-label fallback messaging with a boolean `fallback` (and optional
  `fallback_reason`) field, so `stack = "kmp"` + `fallback = true` instead of
  `"kmp -> kotlin quality-check fallback"`.
- Strip any leading `skill-bill:` (and similar namespace) prefix from `routed_skill` at the MCP
  boundary (`McpLifecycleToolHandlers.kt:106`) before persistence.
- Never emit a blank `routed_skill`/`detected_stack`/`platform_slug`: default to `unknown`/
  `unrouted` when routing does not resolve.
- Update the schema branches and parity tests for the new/normalized fields.

## Acceptance Criteria

1. `review_platform` and `detected_stack` on `review_finished` events are clean enum slugs equal to
   `platform_slug` (no descriptive prose, no fallback narration).
2. A separate descriptive field captures any stack fingerprint removed from the label.
3. kmp→kotlin fallback is expressed as `stack = "kmp"`, `fallback = true` (plus optional
   `fallback_reason`), not as a prose label.
4. No `routed_skill` value carries a `skill-bill:` (or analogous) namespace prefix.
5. No finished event has a blank `routed_skill`, `detected_stack`, or `platform_slug` — unresolved
   routing emits `unknown`/`unrouted`.
6. Schema branches and parity tests reflect the normalized fields and the new `fallback` field.

## Non-goals

- Changing platform-pack discovery or the dominant-stack routing algorithm itself.
- Renaming `platform_slug`.

## Dependencies

None. Feeds Subtask 6 (contract test asserts no blank routing fields).

## Validation strategy

- Unit tests over `ReviewFinishedPayloadSupport` and the MCP boundary: assert slug labels,
  `fallback` flag, prefix stripping, and `unknown` defaults.
- `(cd runtime-kotlin && ./gradlew check)` green.

## Next path

Subtask 5.
