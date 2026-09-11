## [2026-09-11] SKILL-233 subtask 8 — Engine module and package roots
Areas: runtime-kotlin/{runtime-engine,runtime-application,runtime-infra-fs,runtime-infra-sqlite,runtime-contracts,runtime-core,runtime-cli,runtime-mcp,runtime-domain,runtime-ports,agent,ARCHITECTURE.md}
- Extracted feature-task, goal-runner, goal-planning, and planning-projection runtime behavior into the `runtime-engine` module under one `skillbill.engine` root, with a pinned inbound API and no reverse application dependency. reusable
- Moved infrastructure and application packages to their owning roots, using explicit schema-path constants at validator seams so resource lookup remains stable after package relocation. reusable
- Pattern: record module edges and package ownership in one catalog plus architecture tests, and keep empty per-module baselines as proof that new root violations are rejected rather than suppressed. reusable
- Limitation: the extraction and package moves preserve behavior and signatures; further engine subdivision remains outside this change.
Feature flag: N/A
Acceptance criteria: 12/12 implemented
