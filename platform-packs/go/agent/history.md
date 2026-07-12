## [2026-07-11] SKILL-114 Go pack depth
Areas: Go code-review baseline and specialists, Go quality check, native-agent routing, Go pack conformance tests
- Deepened all ten specialist lanes with concrete Go constructs, ecosystem APIs, tools, and observable failure modes while preserving lane ownership.
- Added substantive concurrency, shutdown, race, API, persistence, security, server-rendered UI, CLI/TUI, and accessibility review guidance with applicability gates.
- Expanded quality-check guidance for discovered repository commands, format, vet, static analysis, build, race, vulnerability, module/workspace, generation, and build-tag concerns.
- Followed the reusable manifest-driven routing and provider-neutral native-agent patterns; association-based tests now enforce lane-specific agent descriptions.
- No reusable component or breaking contract was introduced; live `./install.sh` synchronization remains deferred until the runtime goal-continuation guard is inactive.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-10] SKILL-112 Go pack alignment
Areas: Go code-review baseline and specialists, Go platform manifest and quality check, review structure conformance tests
- Aligned all ten Go specialists to the canonical review structure and severity closer while retaining Go as the rule-depth benchmark.
- Added deterministic wave batching plus attributed merge, deduplication, impact calibration, and reachable-precondition checks to the baseline.
- Removed Go from the structure-conformance exemption after preserving existing Go review rules except for standard-required wording and structure changes.
- Followed the reusable manifest-declared routing, native-agent registration, and canonical H2 contracts; no reusable component or breaking contract was introduced.
- Known limitation: live `./install.sh` synchronization remains deferred until the runtime goal-continuation guard is inactive.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
