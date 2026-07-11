---
name: bill-rust-code-check
description: Run Rust project quality checks and fix issues in changed files without suppressions, including formatting, build, tests, lint, dependency policy, and audits.
internal-for: bill-code-check
---

# Rust Quality Check

## Purpose

Validate changed Rust code with the repository's own wrappers, CI commands, toolchain, workspace topology, feature policy, and target matrix. Fix root causes in changed files without installing tools or weakening lint and audit policy.

## Execution Steps

1. Determine changed files and inspect `Cargo.toml`, `Cargo.lock`, workspace members, `rust-toolchain.toml`, CI, `Makefile`, `justfile`, and project scripts.
2. Prefer repository-owned wrappers and the exact CI feature, package, workspace, and target selections.
3. Run formatting verification with the repository command or `cargo fmt --all --check`.
4. Run compilation checks such as `cargo check --workspace --all-targets`, then the repository's required `cargo build` targets.
5. Run tests with the project wrapper, `cargo test`, or `cargo nextest run` when nextest is already configured and available.
6. Run lint with the configured command or `cargo clippy --workspace --all-targets --all-features -- -D warnings` when that feature matrix is supported.
7. Run configured supply-chain checks such as `cargo deny check` and vulnerability checks such as `cargo audit` when the tools are already available or required by the repo.
8. Re-run every affected check after fixes and report commands that could not run, including the missing tool, target, credential, or environment reason.

For workspaces, invoke checks from the workspace root unless project guidance scopes them. Use `-p <member>` for targeted iteration, then restore the required workspace gate. Exercise documented `--no-default-features`, `--all-features`, named features, target triples, examples, benches, and doctests when the change affects them; do not assume all-features is valid for intentionally exclusive features.

## Fix Strategy

- Fix structural, toolchain, workspace, feature-resolution, and generated-code drift before formatting, lint, tests, and audits.
- Use rustfmt rather than hand-formatting and preserve the repository's edition and MSRV.
- Resolve Clippy findings in code; do not add `allow`, baseline, skip, or configuration changes merely to hide failures.
- Preserve `Result` and panic contracts, public API compatibility, safety invariants, and async runtime behavior while fixing diagnostics.
- Update `Cargo.lock` only through the repository's normal Cargo command when dependency metadata genuinely changed.
- Do not install cargo-nextest, cargo-deny, cargo-audit, targets, components, or toolchains during the check; report unavailable required tooling explicitly.
- Do not silently replace a missing repository command with a weaker fallback or skip a required workspace member, feature combination, or target.
