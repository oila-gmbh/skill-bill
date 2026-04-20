---
name: heading_in_fence-code-review
description: Fixture file with a required H2 buried inside a fenced code block to prove the loader refuses to accept that as a real heading.
shell_contract_version: 1.1
template_version: 2026.04.19
---

# Heading In Fence Baseline

## Description
Fixture baseline content.

The following code block shows what a Specialist Scope section could look like,
but no real Specialist Scope H2 exists in this file:

```markdown
## Specialist Scope
This heading is inside a fenced code block and must not count as a real section.
```

## Inputs
Fixture inputs.

## Outputs Contract
Fixture outputs contract.

## Execution Mode Reporting
Fixture execution mode reporting.

## Telemetry Ceremony Hooks
Fixture telemetry hooks.
## Execution

Follow the instructions in [content.md](content.md).
