---
name: bill-quality-check
description: Use when you want a generic quality-check entry point that detects the dominant stack in scope and delegates to the matching stack-specific quality-check skill. Use when user mentions run checks, validate, lint, format, quality check, or run quality.
---

# Shared Quality Check Shell

This skill is a governed **shell**. It owns ceremony, stack routing, and
contract enforcement for the quality-check family. It is deliberately
platform-independent: every piece of stack-specific quality-check reasoning
lives in a user-owned platform pack under
`platform-packs/<platform>/quality-check/<name>/SKILL.md`, registered via the
optional `declared_quality_check_file` key on the pack's `platform.yaml`.

Keep this shell thin:

- detect the dominant stack in the current unit of work via manifest-driven
  discovery
- load the matching platform pack through the shell+content contract and
  resolve its declared quality-check file through
  `skill_bill.shell_content_contract.load_quality_check_content`
- pass through the same scope and relevant context to the routed skill
- refuse to run when a routed pack declares a quality-check file that does
  not exist or is missing a required section — never silently fall back

The shell targets shell contract version **`1.0`**. Packs that do not
declare `declared_quality_check_file` are still contract-compliant;
declaring the key opts the pack into quality-check routing.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-quality-check` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Setup

Determine the current unit of work:
- specific files
- working tree diff
- branch diff / PR scope
- repo-wide validation when the caller explicitly requests it

Inspect the changed files and repo markers before routing.

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For the shared telemetry contract, see [telemetry-contract.md](telemetry-contract.md).

## Shared Stack Detection

Before routing, read [stack-routing.md](stack-routing.md). Use it as the source of truth for:
- stack taxonomy (derived from discovered platform packs)
- signal collection order
- dominant-stack tie-breakers
- mixed-stack routing rules

This supporting file lives beside `SKILL.md`; keep the routing rules in this shell aligned with it.

Do not redefine stack signals here unless a route-specific exception is truly unique to quality-check behavior.

## Routing Rules

Stack detection and routing are manifest-driven. The shell discovers every
`platform-packs/<slug>/platform.yaml`, reads each pack's declared routing
signals, and chooses the dominant stack using the tie-breakers declared in
[stack-routing.md](stack-routing.md). The shell does not enumerate platform
names inline.

- For the dominant pack, read the optional top-level
  `declared_quality_check_file` key from the manifest. The runtime authority
  is `skill_bill.shell_content_contract.load_quality_check_content`.
- The routed skill name is the `name:` of the SKILL.md at
  `platform-packs/<slug>/quality-check/<name>/`. This contract preserves the
  existing `bill-<slug>-quality-check` user-facing commands.
- **Explicit fallback rule:** the `kmp` pack intentionally does NOT declare
  `declared_quality_check_file`. When the dominant pack is `kmp`, route
  quality-check work to the `kotlin` pack's quality-check skill instead.
  Any other pack that does not declare the key loud-fails via
  `MissingContentFileError` — the shell never silently substitutes another
  pack.
- If multiple supported stacks appear in one repo, run the matching
  stack-specific quality checks sequentially, not in parallel, so fixes
  stay deterministic.
- The routed stack-specific skill is the source of truth for build
  commands, filtering rules, and fix strategy.

### Loud-fail contract enforcement

Before executing a routed quality-check, the shell validates the chosen
pack's quality-check file against the shell+content contract. Any failure
stops the run immediately and prints a specific error naming the failing
artifact:

- `MissingContentFileError` — the pack declares `declared_quality_check_file`
  but the referenced file does not exist, or the caller invoked
  `load_quality_check_content` on a pack whose `declared_quality_check_file`
  is `None` without applying the explicit `kmp` → `kotlin` fallback.
- `MissingRequiredSectionError` — the declared quality-check content file is
  missing one of the required H2 sections (`## Description`,
  `## Execution Steps`, `## Fix Strategy`, `## Execution Mode Reporting`,
  `## Telemetry Ceremony Hooks`).

The shell never silently substitutes a default checker. A broken pack must
be repaired before `/bill-quality-check` can complete.

## Delegation Contract

When routing to another skill, pass along:
- the current unit of work and changed files
- the detected stack and key signals
- relevant `AGENTS.md` guidance and matching `.agents/skill-overrides.md` sections
- the rule that the delegated skill must follow its own `SKILL.md` as the primary rubric

## Output Format

```text
Routed to: <skill-name(s)>
Detected stack: <stack> | Mixed | Unknown/Unsupported
Signals: <markers>
Reason: <why this stack-specific quality-checker was selected>

<delegated quality-check output, or "No matching skill available yet" for unsupported>
```

## Telemetry

This shell is thin by design and never emits telemetry on its own —
routing metadata is carried in the concrete stack-specific skill's
telemetry call.

This skill is telemeterable via the `quality_check_started` and `quality_check_finished` MCP tools.

For the shared telemetry contract including the `orchestrated` flag semantics, follow [telemetry-contract.md](telemetry-contract.md).

### Skill-specific telemetry fields

**Standalone invocation** (user runs `bill-quality-check` directly):
1. Call `quality_check_started` once stack routing is decided, with `routed_skill`, `detected_stack`, `scope_type` (`files` / `working_tree` / `branch_diff` / `repo`), and `initial_failure_count` (0 if the first check has not run yet).
2. Save the returned `session_id`.
3. Call `quality_check_finished` when the quality-check loop finishes, with `session_id`, `final_failure_count`, `iterations`, `result` (`pass` / `fail` / `skipped` / `unsupported_stack`), optional `failing_check_names` and `unsupported_reason`.

**Orchestrated invocation** (invoked by another skill such as `bill-feature-implement` that passes `orchestrated=true`):
1. Skip `quality_check_started` entirely.
2. Call `quality_check_finished` once with `orchestrated=true` and all started+finished fields combined (`routed_skill`, `detected_stack`, `scope_type`, `initial_failure_count`, `final_failure_count`, `iterations`, `result`, `failing_check_names`, `unsupported_reason`, `duration_seconds`).
3. The tool returns `{"mode": "orchestrated", "telemetry_payload": {...}}`. Return that payload to the orchestrator — it will embed it in its own finished event.

The orchestrated flag must come from the caller. Never assume orchestrated mode from ambient state.
