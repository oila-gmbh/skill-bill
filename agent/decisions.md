## [2026-04-21] top-level-orchestrators-use-workflow-contract
Context: Skill Bill already has multi-step orchestrators such as `bill-feature-implement`, but their step graph, retry rules, and artifact handoff mostly lived in skill prose and telemetry conventions.
Decision: Add a governed workflow contract under `orchestration/workflow-contract/PLAYBOOK.md` for top-level orchestrators only, starting with `bill-feature-implement`.
Reason: Durable workflow state belongs above reusable skills. This keeps stack routing and rubrics in standalone skills while giving long-running parent flows one authoritative place for step ids, artifacts, resume semantics, and parent-owned telemetry.
Alternatives considered: Turning every skill into a workflow was rejected because it would duplicate leaf-skill contracts and blur the stable user-facing command surface.
Revisit when: Skill Bill is ready to persist workflow state in runtime code or when a second top-level orchestrator adopts the contract.
