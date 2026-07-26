# SKILL-110 - Rust Platform Pack

## Outcome

skill-bill supports Rust as a first-class language platform through a full
manifest-declared `rust` platform pack. Rust projects should route through the
shared code-review and quality-check entry points, receive Rust-specific
baseline and specialist review guidance, validate through the platform-pack
contract, and appear in install/docs surfaces through dynamic manifest
discovery.

## Scope

Add a new `platform-packs/rust/` pack with:

- a valid `platform.yaml` using shell contract version `1.2`
- a baseline `bill-rust-code-review` skill with a provider-neutral
  `native-agents/agents.yaml` source carrying its `contract_version`
- all approved code-review area specialists where Rust has meaningful review
  guidance
- a default `bill-rust-code-check` quality-check skill
- Rust routing signals that identify normal Rust applications, libraries,
  workspaces, and test suites without stealing mixed-stack repos from more
  specific platform packs
- manifest-declared generated pointers and add-on usage only where the platform
  contract supports them
- registration of the `rust` platform-pack preset in the scaffold policy so
  `skill-bill new` can produce the pack shape, mirroring the existing `go` and
  `python` preset entries

The implementation should use existing scaffolder and pack patterns where
possible. If the scaffolder can produce the full pack shape, use it instead of
hand-assembling boilerplate. Authored source remains `content.md`; generated
`SKILL.md` wrappers and pointer files stay out of source.

## Acceptance Criteria

1. `platform-packs/rust/platform.yaml` exists, validates against
   `orchestration/contracts/platform-pack-schema.yaml`, declares slug `rust`,
   display name `Rust`, shell contract version `1.2`, a baseline code-review
   entry, a default quality-check file, and Rust-specific routing signals.
2. The Rust routing signals include strong package and source markers such as
   `Cargo.toml`, `Cargo.lock`, `*.rs`, `build.rs`, `rust-toolchain.toml`,
   `rustfmt.toml`, `clippy.toml`, `deny.toml`, and `.cargo/config.toml`, with
   tie-breaker guidance for mixed repositories (e.g. Rust appearing only as
   FFI bindings, wasm build artifacts, or vendored crates around another
   dominant stack) and exclusion of `target/` build output and vendored
   dependencies from dominance scoring.
3. `bill-rust-code-review` exists under
   `platform-packs/rust/code-review/bill-rust-code-review/content.md` with
   non-placeholder guidance, a specialist routing table, mixed-diff handling,
   and min/max specialist bounds matching the existing platform-pack review
   pattern, plus a provider-neutral `native-agents/agents.yaml` declaring the
   baseline and specialist agents with a `contract_version`.
4. Approved Rust code-review specialists exist under
   `platform-packs/rust/code-review/` with authored, non-TODO content for the
   approved areas that apply to Rust: architecture, performance,
   platform-correctness, security, testing, api-contracts, persistence,
   reliability, ui, and ux-accessibility. Specialist guidance covers
   Rust-specific risk surfaces such as ownership/borrowing misuse, `unsafe`
   blocks, async runtime behavior (tokio/async-std), error-handling contracts
   (`Result`, `panic!`, `unwrap`/`expect`), trait and lifetime API design,
   and Cargo feature-flag hygiene.
5. `bill-rust-code-check` exists under
   `platform-packs/rust/quality-check/bill-rust-code-check/content.md` and
   gives practical Rust validation guidance for common project tooling,
   including `cargo check`, `cargo build`, `cargo test`, `cargo nextest`,
   `cargo clippy`, `cargo fmt --check`, `cargo deny`/`cargo audit`, and
   workspace-aware invocation when present.
6. The scaffold policy registers a `rust` platform-pack preset so scripted and
   interactive scaffolding can target the pack, with payload-map policy
   coverage matching the existing `go` and `python` preset tests.
7. README and relevant user-facing docs/catalogs mention Rust as a shipped
   platform pack without introducing hard-coded discovery behavior that
   bypasses platform manifests.
8. Tests or fixtures cover Rust platform-pack discovery, manifest validation,
   scaffold or install-plan behavior affected by the new pack, and routing or
   validation rejection where a malformed Rust pack would previously pass,
   mirroring the existing per-pack test pattern (e.g. `GoPlatformPackTest`,
   `PythonPlatformPackTest`).
9. `skill-bill validate` passes, Rust pack-specific validation passes, and
   render/install staging can process the Rust pack without committing
   generated wrappers, generated support pointer files, or provider-specific
   native-agent output.

## Non-Goals

- Do not create legacy `skills/rust/` platform overrides.
- Do not add Rust as a hard-coded special case in routing, install, desktop,
  or validation paths when the manifest contract can drive the behavior.
- Do not add new code-review area names outside the approved taxonomy.
- Do not support every Rust framework in depth; focus on broadly applicable
  crate, application, library, API, async-service, and test-review guidance.
- Do not add Rust-specific quality-check fallback wiring beyond what the
  manifest contract already provides.

## Constraints

- Source skill directories under the platform pack contain `content.md` only,
  except for allowed `native-agents/` sources.
- Generated `SKILL.md` wrappers, support pointers, and provider-specific agent
  outputs are not committed.
- Any schema or runtime contract changes fail loudly through typed errors and
  include parity coverage when required.
- After source skill, renderer, support pointer, or install-staging changes,
  run `./install.sh` so the local install reflects the new staging hash.

## Validation Strategy

Run the project validation commands appropriate to the changed surface:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

At minimum, also run targeted Rust platform validation/render commands for
`bill-rust-code-review`, its specialists, and `bill-rust-code-check` before
the full suite when iterating.
