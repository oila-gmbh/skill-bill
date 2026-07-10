---
name: bill-rust-code-check
description: Run Rust project quality checks and repair changed-file issues without suppressions, covering formatting, compilation, linting, tests, security, and dependency policy.
internal-for: bill-code-check
---

# Rust Quality Check

## Execution Steps

1. Identify changed files and the owning Cargo packages or workspace members.
2. Discover repository-owned Make, just, task, CI, or script wrappers and prefer them over bare Cargo commands.
3. Run the smallest faithful checks first, repair root causes without suppressions, then run the repository's broader gate.
4. Report unavailable optional tools; do not install them or rewrite configuration merely to pass.

## Rust Commands

- Use workspace-aware or package-aware `cargo check`, then `cargo build` when build artifacts or target behavior matter.
- Run `cargo test`; use `cargo nextest run` when the repository configures nextest. Include doc tests, examples, benches, integration tests, and target-specific tests when affected.
- Run `cargo clippy --all-targets` with repository-defined feature flags and warning policy; do not add `allow` attributes to hide actionable findings.
- Run `cargo fmt --check` using the pinned toolchain and repository `rustfmt.toml`.
- Run `cargo deny check` when `deny.toml` or a project wrapper exists, and `cargo audit` when configured or already available.
- Respect workspace `default-members`, `--workspace`, `-p`, `--manifest-path`, target triples, `--all-features`, `--no-default-features`, and explicit feature combinations instead of assuming one universal invocation.

## Repair Rules

Preserve ownership and error contracts, Cargo feature hygiene, lockfile policy, MSRV/toolchain constraints, and generated-source boundaries. Regenerate through project commands, never edit generated output by hand. Check `Cargo.lock` changes for the repository's library/application policy and do not broaden dependencies or features just to silence a check.
