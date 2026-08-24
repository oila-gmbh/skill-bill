# SKILL-207 — Prose-centric review phase I/O

## Context

Skill Bill runs agent work across process boundaries: a review worker exits, then
claim verification, remediation, or a human follow-up may start in another
session. Today the review boundary treats the F-XXX risk register as a **typed
API**. `ParallelReviewFindingParser` admits only an exact positional shape. A
near-miss line — markdown backticks, area labels where confidence belongs,
missing `:line` — is counted as a candidate and dropped as
`unmatched_candidate_line`. The review run stays green (soft-admit), but the
finding disappears from the handoff. Recent standalone inline runs on
`2e17a490` showed exactly that: the worker produced a useful defect line; the
Kotlin seam erased it before anything else could use it.

That design fights how non-deterministic agents behave. Prompting for shape X
reduces drift; it cannot eliminate it. Agents reason in prose. Variations of an
expected form are normal output, not failure. Skill Bill’s philosophy is to
**embrace that prose** and let the Kotlin runtime **govern** phases (launch,
routing, evidence tools, budgets, persistence, resume) without policing the
grammar of the thinking.

Inter-phase communication should therefore be string-centric:

- **Input:** what the phase is handed, plus what we ask it to do with that input
- **Output:** what the phase produced (prose, best-effort structured hints included)

Claim verification stays. It is not a regex consumer of admitted rows. It is
another phase call: hand it the review output string and ask it to verify each
claim in that input.

This skill starts with **review** (standalone `bill-code-review` / inline worker
and the review→verify seam). Other feature-task phases adopt the same envelope
later; they are out of scope here except as a named next path.

## Intended Outcome

Review is a governed agent phase whose authoritative result is a prose
`output` string. The runtime passes `input` and `requestedAction` into the
phase and persists or forwards `output` without a shape gate that can delete
near-miss findings.

Prompts still ask for the F-XXX register best-effort. Near-miss register text
remains in `output`. Claim verification runs as the same envelope: `input` =
review `output`, `requestedAction` = verify the claims in that input. No
admission parser sits between those phases as a filter that drops content.

A single shared Kotlin input/output model (or a pair if input and output stay
separate types) is introduced for this pattern so later phases can reuse it
without inventing a new schema per verb.

## Acceptance Criteria

1. A shared phase I/O model exists for agent phases with at least
   `input: String` and `requestedAction: String` on the inbound side and
   `output: String` on the outbound side (enrichment fields allowed only when
   they do not replace or shadow the string as the authoritative agent result).
2. The review phase (standalone `skill-bill code-review` / `ParallelCodeReviewRunner`
   path, including `mode:inline`) uses that model: the worker’s returned text is
   the authoritative `output`, and near-miss `[F-XXX]` lines are not removed from
   that handoff by register admission.
3. Register-shape prompting remains best-effort guidance in review skills and
   parent prompts; imperfect shape never causes the review result to become an
   empty stand-in when the worker returned substantive text.
4. Claim verification still runs when the review pipeline requires it, and it
   is invoked as a phase call over the review `output` string with a
   `requestedAction` that asks to verify claims in that input — not as a
   consumer that only sees regex-admitted structured findings.
5. `bill-code-review` and `bill-code-review-inline` governed content describe
   this prose-centric boundary (govern with Kotlin; meaning stays with the
   agent; shape X is best-effort).
6. Existing non-agent launch plumbing (repo root, agent id, evidence broker
   binding, budgets) may stay typed outside the phase I/O envelope; that setup
   is not reclassified as the agent result contract.
7. Automated tests cover: a near-miss register line that today’s parser would
   reject still appears in review `output`, and claim verification is launched
   from that string envelope (or an equivalent seam assertion at the phase
   boundary).

## Constraints

- Do not expand this skill to rewrite plan / implement / validate / commit
  phases onto the new envelope; review (+ review→verify) only.
- Do not remove claim verification; reframe its handoff.
- Do not require a second LLM “format repair” pass as a substitute for keeping
  the raw output.
- Soft-admit / diagnostic behaviour may remain for observability, but must not
  be the path that strips the only copy of a finding from the phase result.
- Preserve loud-fail for real infrastructure failures (missing native worker,
  contract/pack errors, launch capability). Those are governance, not prose
  policing.
- Keep evidence-broker and governed-tool constraints for review workers; this
  skill changes the I/O philosophy, not the read-only evidence contract.

## Non-Goals

- Making every feature-task phase use the new I/O types in this change.
- Perfect register parsing, wider regex recovery, or backtick-only patches as
  the solution (recovery patches are obsolete once the string is authoritative).
- Replacing severity vocabulary, risk-register *prompt* guidance, or human-facing
  review presentation conventions.
- Re-introducing provider token accounting or other unrelated review accounting
  work.
- Changing parallel lane-2 agent selection or pack routing rules.

## Decomposition Rationale

Two subtasks:

1. **Envelope + review handoff** — introduce the shared I/O types and make
   review’s authoritative result the agent string (no drop-as-filter).
2. **Verify + skills** — drive claim verification from that string envelope and
   align governed review skill content.

Split because verify cannot be specified against the old admitted-finding list
until review’s result contract has moved, and skill wording must describe the
shipped boundary rather than aspirational prose.

## Next Path

After review lands: adopt the same `input` / `requestedAction` / `output`
envelope for remaining feature-task phases one at a time, starting with the
next phase that today loses meaning to a shape gate or re-encodes agent text
into a brittle typed API.
