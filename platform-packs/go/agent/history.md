## [2026-07-10] SKILL-112 Go pack alignment
Areas: Go code-review baseline and specialists, Go platform manifest and quality check, review structure conformance tests
- Aligned all ten Go specialists to the canonical review structure and severity closer while retaining Go as the rule-depth benchmark.
- Added deterministic wave batching plus attributed merge, deduplication, impact calibration, and reachable-precondition checks to the baseline.
- Removed Go from the structure-conformance exemption after preserving existing Go review rules except for standard-required wording and structure changes.
- Followed the reusable manifest-declared routing, native-agent registration, and canonical H2 contracts; no reusable component or breaking contract was introduced.
- Known limitation: live `./install.sh` synchronization remains deferred until the runtime goal-continuation guard is inactive.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
