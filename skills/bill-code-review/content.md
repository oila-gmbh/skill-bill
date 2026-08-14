---
name: bill-code-review
description: Dominant-stack code-review entry point. Use when reviewing code, reviewing a PR, reviewing staged changes, or when the user asks for a code review.
---

# Code review entry

## Review mode argument

Recognize at most one `mode:auto`, `mode:inline`, or `mode:delegated` argument.
Omission means `mode:inline`.
Reject malformed, unknown, duplicate, or conflicting values before invoking the
driver.

Recognize at most one `context:feature-remediation` argument. It is valid only
with `mode:inline` when a governed feature-task caller supplies the exact
remediation delta since its checkpoint. Reject it with another mode, a full
branch/PR scope, or no bounded remediation scope.

`delegated` and `inline` are two review depths, not two ways to execute the same
review. Report the requested mode and the resolved depth in the normal review
metadata.

`inline` is the default depth. `delegated` is the experimental full-depth tier and
runs only on an explicit `mode:delegated`, or an explicit `code-review:delegated`
selection carried down from a governed feature caller. Neither an omitted argument
nor `mode:auto` ever reaches it.
Choose it when a change genuinely warrants per-area depth, not by default.

`delegated` always runs the normal routed delegated path
including specialist selection. Inability to launch a required native worker
blocks loudly; it never degrades to inline.

`inline` is the single-prompt light tier: one review subagent launched by the
driver as the declared `bill-code-review-inline` native agent, no per-area
specialist workers, no nested baseline orchestrator, under a bounded budget at
reduced depth. The worker traverses the delta exactly once against one combined
checklist, holding all areas in mind simultaneously — it must never re-walk the
same delta once per area. Never present it as equivalent to a delegated result.

`auto` resolves to `inline` everywhere: a subtask's first review pass, a standalone
review with no pass number, and every follow-up or remediation pass. Preserve and
report the applicable named auto rule for telemetry. `auto` never reaches the
experimental delegated tier — only an explicit `mode:delegated` does.

Depth is the only thing the light tier lowers. The severity vocabulary, the
finding admission gate, the evidence and observable-consequence requirements, the
F-XXX risk register format, and telemetry are inherited unchanged and are never
restated per tier.

With `context:feature-remediation`, the pass is bounded to the supplied
remediation delta — all findings addressed in that round unioned with the
pre-fix-to-post-fix diff — rather than the full base-to-current delta, and
verification is its primary output. For every Blocker the prior pass emitted,
state `resolved`, `unresolved`, or `superseded` under the durable
`blocker_dispositions` key, and cite the specific changed lines that settle it.
A disposition without that evidence is not admissible.

## Parallel lane argument

When the caller passes `parallel:<agent>` or `parallel:<agent>:<model>` in args —
for example `parallel:codex`, `parallel:codex:o3`, or
`parallel:claude:claude-opus-4-8` — add a second lane on the same driver. Both
lanes share the resolved depth. Do not pass `parallel:` into lane 2.

Recognise `parallel:<agent>` or `parallel:<agent>:<model>` where `<agent>` is a
supported agent ID and `<model>` is an optional model override for lane 2.
Parse by splitting on `:` — the first token is always `parallel`, the second is
the agent ID, and the optional third token is the model. Any further colons are
part of the model ID. If `<agent>` is blank or unsupported, stop immediately,
name the unsupported value, and list supported agents. Do not invoke the driver.

## Config fallback when `parallel:` is absent

The `parallel:` arg, when present, always wins. When it is absent, the driver
resolves lane 2 with precedence `parallel: arg > code_review_parallel_agent
config > none`. A missing file, missing key, or explicit `none` is single-lane.
An unrecognized config value or malformed config is a hard failure naming
`code_review_parallel_agent`; do not treat it as `none`.

Resolve the effective lane-2 agent with:

```bash
skill-bill config resolve-parallel-agent --repo-root <repo-root>
```

Pass a supported agent id through as `--agent2`. Pass nothing when the result is
`none`.

## Invoke the driver

Do not resolve scope, classify diff signals, name rubrics, sequence commits,
account budgets, merge lanes, or launch workers in this session. Invoke the
runtime driver once and present what it returns:

```bash
skill-bill code-review \
  --execution-mode <resolved-mode> \
  --scope <caller-scope> \
  --repo-root <repo-root>
```

Add `--agent2 <id>` when a `parallel:` arg or a non-`none` config fallback
selected a second lane. Add `--model2 <model>` only when `parallel:<agent>:<model>`
supplied a model. Pass `--diff-file` with paired `--base-revision` and
`--head-revision` when the caller already materialized an exact diff. Pass
`--baseline-untracked-include` / `--baseline-untracked-exclude` when the caller
supplied that inventory.

When a governed feature caller supplies a labelled `Selected agent add-ons`
section, treat that section as an immutable compact-context field. The driver
forwards it; do not rediscover add-ons.

## Present the register

Display the driver's stdout as the review result. It already includes the risk
register with provenance labels and any recorded stage verdicts. Do not rewrite
findings, invent a second merge, or re-run the review in this session.
