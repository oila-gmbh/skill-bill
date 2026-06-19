# Skill Bill demo storyboard — `/bill-feature-task` (spec → PR)

This is the shot-by-shot plan for the front-door demo. It captures **one** concrete
flow: running `/bill-feature-task` against a small spec and watching it walk
all the way from a design doc to a merged-ready PR, with durable workflow state and
telemetry visible along the way.

The recorded asset is produced out-of-band (see
[skill-bill-demo-capture-instructions.md](skill-bill-demo-capture-instructions.md))
and swapped in for the static placeholder
([skill-bill-demo-placeholder.svg](skill-bill-demo-placeholder.svg)) once captured.

- **Target length:** 18–24 seconds
- **Aspect / resolution:** 16:9, captured at 1920×1080, scaled to 1200px wide for README embed
- **Pacing:** brisk; no dead air. Fast-forward (4×–8×) any long model "thinking" gaps.
- **Tone:** calm and confident — "deep machinery, one command."

## Shot list

| # | Time | Scene | On-screen action | Caption / overlay text |
|---|------|-------|------------------|------------------------|
| 1 | 0:00–0:02 | Clean terminal in the cloned repo | Cursor blinking at an empty prompt; repo name visible in the title bar | `skill-bill` |
| 2 | 0:02–0:05 | Show the spec | `bat spec.md` (or open it) revealing a short feature spec with acceptance criteria | "Start with a spec." |
| 3 | 0:05–0:07 | Invoke the workflow | Type and run `/bill-feature-task spec.md` in the agent | "One command." |
| 4 | 0:07–0:11 | Phase ladder | Phase lines stream in: assess → branch → pre-plan → plan → implement → review → audit → quality-check (fast-forward the slow gaps) | "Each phase runs in its own subagent." |
| 5 | 0:11–0:14 | Durable state | A line confirming `feature_task_prose_workflow_update` at a phase boundary; briefly show `skill-bill workflow latest` | "Durable state — crash anywhere, resume cleanly." |
| 6 | 0:14–0:17 | Telemetry tree | Show the parent/child telemetry rollup (e.g. `skill-bill implement-stats`) | "Structured telemetry, parent → child." |
| 7 | 0:17–0:21 | PR handoff | Generated PR title + description + QA steps printed; "PR ready" line | "Spec → merged-ready PR." |
| 8 | 0:21–0:24 | Logo rest frame | Fade to the Skill Bill hero mark | "Skill Bill" |

## Notes for the capture operator

- Use a real (small) spec so the run completes quickly and honestly; do not fabricate output.
- It is fine to trim/fast-forward model latency between phases, but **do not** reorder or invent phases — the ladder in shot 4 must match the actual pipeline order.
- Keep secrets, tokens, and private repo names out of frame.
- If the full run is too long for a tight loop, cut at shot 7 ("PR ready") and drop the rest frame.

## Alternate flow (if the spec→PR run is hard to film cleanly)

Storyboard the **desktop app** instead: launch the Compose Desktop app, show the
tree-based skill/artifact browser, run the scaffold wizard to author a new skill,
and show the tree selection jump to the newly created file — same 18–24s budget,
same "deep machinery, trivial to use" message.
