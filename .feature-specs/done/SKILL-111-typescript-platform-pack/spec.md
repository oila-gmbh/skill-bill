# SKILL-111 - TypeScript Platform Pack

## Outcome

skill-bill supports TypeScript as a first-class language platform through a
full manifest-declared `typescript` platform pack. TypeScript projects should
route through the shared code-review and quality-check entry points, receive
TypeScript-specific baseline and specialist review guidance, validate through
the platform-pack contract, and appear in install/docs surfaces through
dynamic manifest discovery.

## Scope

Add a new `platform-packs/typescript/` pack with:

- a valid `platform.yaml` using shell contract version `1.2`
- a baseline `bill-typescript-code-review` skill with a provider-neutral
  `native-agents/agents.yaml` source carrying its `contract_version`
- all approved code-review area specialists where TypeScript has meaningful
  review guidance
- a default `bill-typescript-code-check` quality-check skill
- TypeScript routing signals that identify normal TypeScript applications,
  libraries, monorepos, and test suites without stealing mixed-stack repos
  from more specific platform packs
- manifest-declared generated pointers and add-on usage only where the
  platform contract supports them
- registration of the `typescript` platform-pack preset in the scaffold policy
  so `skill-bill new` can produce the pack shape, mirroring the existing `go`,
  `python`, and `rust` preset entries

The implementation should use existing scaffolder and pack patterns where
possible. If the scaffolder can produce the full pack shape, use it instead of
hand-assembling boilerplate. Authored source remains `content.md`; generated
`SKILL.md` wrappers and pointer files stay out of source.

## Acceptance Criteria

1. `platform-packs/typescript/platform.yaml` exists, validates against
   `orchestration/contracts/platform-pack-schema.yaml`, declares slug
   `typescript`, display name `TypeScript`, shell contract version `1.2`, a
   baseline code-review entry, a default quality-check file, and
   TypeScript-specific routing signals.
2. The TypeScript routing signals include strong source and configuration
   markers such as `tsconfig.json`, `tsconfig.*.json`, `*.ts`, `*.tsx`,
   `*.mts`, `*.cts`, and TypeScript-aware lockfile/config context
   (`package.json` with lockfiles such as `package-lock.json`, `yarn.lock`,
   `pnpm-lock.yaml`, `bun.lockb`, plus `biome.json` and ESLint/Prettier
   configs), with tie-breaker guidance for mixed repositories (e.g.
   TypeScript appearing only as generated API clients, ambient `*.d.ts`
   declarations, or build tooling around another dominant stack) and
   exclusion of `node_modules/`, `dist/`, build output, and generated
   declaration files from dominance scoring.
3. `bill-typescript-code-review` exists under
   `platform-packs/typescript/code-review/bill-typescript-code-review/content.md`
   with non-placeholder guidance, a specialist routing table, mixed-diff
   handling, and min/max specialist bounds matching the existing
   platform-pack review pattern, plus a provider-neutral
   `native-agents/agents.yaml` declaring the baseline and specialist agents
   with a `contract_version`.
4. Approved TypeScript code-review specialists exist under
   `platform-packs/typescript/code-review/` with authored, non-TODO content
   for the approved areas that apply to TypeScript: architecture,
   performance, platform-correctness, security, testing, api-contracts,
   persistence, reliability, ui, and ux-accessibility. Specialist guidance
   covers TypeScript-specific risk surfaces such as type-safety erosion
   (`any`, unchecked casts, non-null assertions), `strict`-mode and narrowing
   correctness, async/promise handling (unawaited promises, error
   propagation, race conditions), null/undefined boundaries at I/O and API
   seams, runtime-vs-compile-time validation gaps, module/bundling behavior,
   and Node-vs-browser runtime assumptions.
5. `bill-typescript-code-check` exists under
   `platform-packs/typescript/quality-check/bill-typescript-code-check/content.md`
   and gives practical TypeScript validation guidance for common project
   tooling, including `tsc --noEmit`, ESLint, Biome, Prettier check mode,
   Vitest/Jest, package-manager-aware script invocation (npm, yarn, pnpm,
   bun), and monorepo-aware invocation (workspaces, turbo, nx) when present.
6. The scaffold policy registers a `typescript` platform-pack preset so
   scripted and interactive scaffolding can target the pack, with payload-map
   policy coverage matching the existing preset tests.
7. README and relevant user-facing docs/catalogs mention TypeScript as a
   shipped platform pack without introducing hard-coded discovery behavior
   that bypasses platform manifests.
8. Tests or fixtures cover TypeScript platform-pack discovery, manifest
   validation, scaffold or install-plan behavior affected by the new pack,
   and routing or validation rejection where a malformed TypeScript pack
   would previously pass, mirroring the existing per-pack test pattern
   (e.g. `GoPlatformPackTest`, `PythonPlatformPackTest`).
9. `skill-bill validate` passes, TypeScript pack-specific validation passes,
   and render/install staging can process the TypeScript pack without
   committing generated wrappers, generated support pointer files, or
   provider-specific native-agent output.

## Non-Goals

- Do not create legacy `skills/typescript/` platform overrides.
- Do not add TypeScript as a hard-coded special case in routing, install,
  desktop, or validation paths when the manifest contract can drive the
  behavior.
- Do not add new code-review area names outside the approved taxonomy.
- Do not add a separate JavaScript platform pack or JavaScript-only routing;
  plain-JS handling beyond what TypeScript signals naturally cover is out of
  scope.
- Do not support every TypeScript framework in depth; focus on broadly
  applicable application, library, API, frontend, backend, and test-review
  guidance.

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

At minimum, also run targeted TypeScript platform validation/render commands
for `bill-typescript-code-review`, its specialists, and
`bill-typescript-code-check` before the full suite when iterating.
