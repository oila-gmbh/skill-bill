---
status: In Progress
---

# SKILL-58 Subtask 2 - Durable-State Reconciliation + Stale-Row Hygiene

Parent spec: [.feature-specs/SKILL-58-goal-runner-operational-reliability/spec.md](./spec.md)
Issue key: SKILL-58

Ensure `goal status`, workflow-store runtime state, and checked-in decomposition
projection remain consistent, and reconcile stale `running` child workflow rows
to typed terminal outcomes.
