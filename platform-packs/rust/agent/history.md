# Boundary History — platform-packs/rust

## [2026-07-11] SKILL-114 rust-pack-depth
Areas: platform-packs/rust, orchestration/review-orchestrator, runtime-kotlin/runtime-infra-fs tests
- Deepened all ten Rust review specialists with mechanism-specific ownership, unsafe/FFI, async, API, persistence, security, testing, interface, and operational failure analysis.
- Expanded the Rust quality checker from discovered commands through Cargo workspace, format, clippy/check/build/test/doc, feature/target, dependency, bindings, and configured unsafe-analysis paths.
- Added reusable substance and cross-pack duplication regression contracts for every Rust specialist role, while retaining applicability gates for optional tools and interface families. reusable
- No breaking contract changes; live install sync remains deferred while the runtime workflow-store guard is active.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-11] SKILL-110 rust-platform-pack
Areas: platform-packs/rust, runtime-kotlin/scaffold, runtime-kotlin/native-agent composition, runtime-kotlin/runtime-infra-fs tests, README.md, docs
- Added the shipped `rust` pack with manifest-driven routing, a baseline review router, ten approved specialists, a default quality check, and provider-neutral native-agent declarations.
- Rust dominance favors Cargo and source/config markers while discounting `target/`, vendored crates, FFI-only bindings, and wasm artifacts in mixed repositories. reusable
- Enforced manifest-derived baseline-plus-specialist native-agent completeness for every platform pack, with typed schema failures and canonical scaffold rendering. reusable
- Added Rust scaffold preset, discovery, validation rejection, render, install-plan, and payload-policy coverage without hard-coded discovery or generated source artifacts.
- Known limitation: live `./install.sh` sync remains deferred by the active workflow-store guard; the equivalent branch-local distribution and read-only Rust install plan passed.
Feature flag: N/A
Acceptance criteria: 9/9 implemented
