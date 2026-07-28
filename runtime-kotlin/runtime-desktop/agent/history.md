# runtime-desktop — history

## [2026-07-28] SKILL-132 desktop and final dead-code cleanup
Areas: runtime-desktop/core/data, runtime-desktop/core/designsystem, runtime-desktop/core/domain, runtime-application/featuretask
- Removed unused placeholder services, design-system tokens/theme helpers, and the unreferenced first-run detected-agent model after source, generated, resource, and packaging consumer checks.
- Retained generated-code-only desktop providers and repositories; future dead-code sweeps must inspect KSP/DI output before removing apparently unreferenced composition declarations.
- Audit-remediation prompts now turn oversized artifact/check reference schema failures into bounded single-pointer correction guidance.
- No reusable runtime components or feature flag were introduced; no known breaking change remains.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-06-11] SKILL-52.4 desktop boundary leak closure
Areas: runtime-desktop/core/common, runtime-desktop/core/domain, runtime-desktop/core/data, runtime-desktop/core/testing, runtime-desktop/feature/skillbill, runtime-core
- Desktop commonMain no longer imports java.io/java.nio or literal Dispatchers.; baseline services accept common-safe string paths and route execution through injected DispatcherProvider. reusable
- RecentRepoRepository is suspend end-to-end, and SkillBillViewModel startup uses begin/run/finish async paths instead of constructor-time runBlocking or repo opening.
- RuntimeDesktopBoundaryTest now enforces commonMain JVM-import bans, commonMain dispatcher-literal bans, and non-test jvmMain runBlocking restrictions for future desktop work. reusable
- Golden/wire behavior unchanged; this slice touched desktop runtime boundaries and architecture guards, not renderer or workflow wire outputs.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
