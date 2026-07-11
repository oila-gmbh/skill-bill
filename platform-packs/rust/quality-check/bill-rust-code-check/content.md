---
name: bill-rust-code-check
description: Run Rust project quality checks and fix issues in changed files without suppressions, including formatting, build, tests, lint, dependency policy, and audits.
internal-for: bill-code-check
---

# Rust Quality Check

## Purpose

Validate changed Rust code with the repository's own wrappers, CI commands, toolchain, workspace topology, feature policy, and target matrix. Fix root causes in changed files without installing tools or weakening lint and audit policy.

## Execution Steps

1. Determine the files in scope and inspect `Cargo.toml`, `Cargo.lock`, workspace members, `rust-toolchain.toml`, `.cargo/config.toml`, CI, `Makefile`, `justfile`, and repository scripts. Discover commands from the build file, repository wrapper, and CI configuration, in that order, before falling back to Cargo defaults.
2. Start at the Cargo workspace root unless project guidance owns a narrower entrypoint. Use the wrapper's exact toolchain, environment, packages, features, targets, and locked/frozen policy; use `cargo metadata --locked --no-deps` when that is part of repository integrity checking.
3. Run the pack's quality-check entrypoint through the discovered repository wrapper with its exact CI feature, package, workspace, and target selections.
4. Verify formatting first with the discovered command or `cargo fmt --all --check` so later diagnostics refer to canonical source.
5. Run configured linting with the repository command or `cargo clippy --workspace --all-targets -- -D warnings`. Add `--all-features` only when the repository supports that combination; otherwise run its documented representative feature sets.
6. Run type and conditional-compilation checks with `cargo check --workspace --all-targets`, then required build targets with `cargo build --workspace`. Preserve repository `--locked`, profile, package, and target flags.
7. Run behavior checks through the project wrapper, `cargo test --workspace`, or `cargo nextest run` only when nextest is configured and available. Run `cargo test --doc` when public documentation is in scope and doctests are not already included by the wrapper.
8. Exercise documented `--no-default-features`, named features, target triples, examples, benches, and package combinations affected by the change. Do not require mutually exclusive all-feature combinations, unavailable cross-target runtimes, or unrelated workspace members during targeted iteration.
9. Verify dependency and lockfile policy with configured commands such as `cargo deny check`, `cargo audit`, or a repository wrapper. Run them only when required by the repository and already provisioned; do not install optional tools or update the lockfile merely to perform a check.
10. When generated bindings are owned by the repository, run its `bindgen`, `cbindgen`, UniFFI, wasm-bindgen, or other generation verification and fail on drift. Do not regenerate or require binding tools when the project does not configure that path.
11. When changed unsafe code is covered by an existing policy, run configured analysis such as `cargo miri test` with the repository's selected target and features. Report an environmental blocker if the required component or target is unavailable rather than installing it or silently skipping the gate.
12. Attribute each failure to the changed files or scoped work when evidence supports ownership. Identify unrelated pre-existing failures separately with command output and do not mutate out-of-scope code merely to make the run green.
13. Re-run the smallest affected package, test target, or command after each fix, then escalate to the full repository-required suite. Report every command that cannot run with the missing tool, target, runtime, credential, service, or maintainer decision.

## Fix Strategy

- Use a priority-ordered fix strategy and never suppress failures.
- Fix structural, toolchain, workspace, feature-resolution, and generated-code drift before formatting, lint, tests, and audits.
- Use rustfmt rather than hand-formatting and preserve the repository's edition and MSRV.
- Resolve Clippy findings in code; do not add `allow`, baseline, skip, or configuration changes merely to hide failures.
- Preserve `Result` and panic contracts, public API compatibility, safety invariants, and async runtime behavior while fixing diagnostics.
- Update `Cargo.lock` only through the repository's normal Cargo command when dependency metadata genuinely changed.
- Do not install cargo-nextest, cargo-deny, cargo-audit, targets, components, or toolchains during the check; report unavailable required tooling explicitly.
- Do not silently replace a missing repository command with a weaker fallback or skip a required workspace member, feature combination, or target.
- Treat a required but unavailable service, credential, tool, target, runtime, or maintainer decision as an environmental blocker with the exact blocked command.
- Re-run targeted checks after each fix category. Run the full suite when targeted checks cannot establish safety; otherwise run the full repository gate required by policy before completion.
