---
name: bill-feature-implement
description: Use when doing end-to-end feature implementation from design doc to verified code. Automatically scales ceremony based on feature size - lightweight for small changes, full orchestration for medium features, and explicit decomposition into resumable subtask specs when work is too large for one reliable execution. Runs each heavy phase (pre-planning, planning, implementation, completeness audit, quality check, PR description) inside its own subagent with a rich self-contained briefing, to keep the orchestrator context small. Code review stays in the orchestrator because it already spawns specialist subagents internally. Use when user mentions implement feature, build feature, implement spec, or feature from design doc.
---

## Descriptor

Governed skill: `bill-feature-implement`
Family: `workflow`
Description: Use for feature implement work.

## Execution

Follow the instructions in [content.md](content.md).

## Ceremony

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

When telemetry applies, follow [telemetry-contract.md](telemetry-contract.md).
