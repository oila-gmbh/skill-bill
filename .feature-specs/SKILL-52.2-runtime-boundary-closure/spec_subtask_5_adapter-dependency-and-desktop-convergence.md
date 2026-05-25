# SKILL-52.2 Subtask 5 - Adapter Dependency Narrowing + Desktop Gateway Convergence

Parent spec: [.feature-specs/SKILL-52.2-runtime-boundary-closure/spec.md](./spec.md)
Issue key: SKILL-52.2
Subtask order: 5 of 5
Depends on: subtasks 2-4
Branch model: same-branch, commit per subtask

## Purpose

Close the remaining architectural enforcement gap: adapters should not merely
avoid forbidden imports while retaining broad classpaths. Desktop gateway code
should also consume shared typed runtime results without duplicating business
policy.

## Scope

In scope:

- Narrow Gradle dependencies for:
  - `runtime-cli`;
  - `runtime-mcp`;
  - `runtime-desktop:core:data`;
  - affected desktop feature modules.
- Keep only dependencies required by direct source imports and generated
  Kotlin-Inject ABI.
- If `runtime-core` must remain visible to an adapter, document the exact
  generated/public type requiring it and assert that edge in architecture tests.
- Remove direct adapter dependencies on `runtime-domain`, `runtime-ports`, or
  `runtime-infra-*` where typed application services and mappers make them
  unnecessary.
- Strengthen architecture tests:
  - module dependency allow-lists should be minimal and exact;
  - adapters must not regain broad infra/domain classpaths without a named
    exception;
  - runtime-core public ABI closure must not grow.
- Desktop:
  - Update desktop data gateways to map from shared typed runtime application
    results.
  - Add tests that desktop gateway mappings do not reimplement scaffold/review/
    telemetry/install policy.
  - Keep UI-specific state and view models desktop-owned.
- Finalize docs/history:
  - update `runtime-kotlin/ARCHITECTURE.md`;
  - update `runtime-kotlin/agent/history.md`;
  - add decisions if any public ABI edge or open-boundary exception remains.
- Run the full validation suite.

Out of scope:

- Desktop UI redesign.
- New desktop features.
- Native package changes.

## Acceptance Criteria

1. Adapter Gradle dependency allow-lists are narrower than the SKILL-52.1
   baseline and enforced by architecture tests.
2. `runtime-core` remains composition-only, with no infrastructure or entrypoint
   public API growth.
3. Desktop data gateways consume shared typed runtime results and tests prove
   business policy stays in runtime application/domain layers.
4. `runtime-kotlin/ARCHITECTURE.md`, `RuntimeModule`, and architecture tests
   agree.
5. Boundary history/decisions are updated for reusable patterns and retained
   exceptions.
6. Full validation passes.

## Validation

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

