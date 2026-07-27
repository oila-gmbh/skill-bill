## [2026-07-27] SKILL-144 generic code-review fallback contract and routing
Areas: orchestration contracts, generic platform pack, review routing, filesystem pack loading
- Added a manifest-declared generic code-review pack with the baseline skill, all approved specialists, and provider-neutral native-agent sources.
- Anchored fallback ownership in the platform-pack schema and typed loader; duplicate or incoherent declarations fail with typed contract errors.
- Review routing now requires positive path ownership for concrete packs, uses content only to break equal positive scores, and selects generic for zero-owner or unresolved-tie cases.
- Preserved governed concrete composition such as KMP-over-Kotlin and excluded generic whenever routing finds a clear concrete owner.
- Reusable: manifest-driven fallback ownership and the generic specialist set provide the stack-independent review path.
- Known limitation: installation and complete review-launch integration remain for the dependent follow-up subtask.
Feature flag: N/A
Acceptance criteria: 9/9 implemented
