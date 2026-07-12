# TypeScript Dependency-Injection Backend Review Add-On

> SCAFFOLD (SKILL-116). Structure and activation signals are authored; the
> per-topic rules are TODO. This file cannot pass the maintained substance gate
> until the companion topic files exist and each carries concrete, reachable
> failure modes. Do not ship as-is.

Use this governed add-on only after stack routing has already selected `typescript`
and the review scope touches a decorator-based dependency-injection backend — a
NestJS-style container with `@Module`/`@Injectable` registration, provider scopes,
resolver/controller adapters, and command/query handlers.

This file is a review index for `bill-typescript-code-review` and its
`architecture`, `reliability`, and `security` specialists. It is not a standalone
review command. The guidance is generic to decorator-DI TypeScript backends; it
encodes recurring failure modes, not any single project's internals.

## Activation signals

Activate `di-backend` when the diff shows any of:

- provider/module registration or scope declaration (`@Module`, `@Injectable`, a
  `providers`/`exports` array, `Scope.REQUEST`/`Scope.TRANSIENT`, `forwardRef`)
- a resolver/controller adapter delegating to a command/query handler, or a
  handler injecting repositories/other providers
- a request-scoped execution/user context injected into a provider, or a provider
  holding mutable instance state
- a cross-module import added to reach another domain's data or permission surface

If the diff only touches pure domain functions, plain modules with no DI, or
framework-free code, do **not** activate this add-on.

## Section index

Scan this file first. Then open only the linked topic files whose cues match the
diff instead of loading all DI guidance by default.

- `di-backend-scope-and-state.md` *(TODO — create)*
  Read when the diff touches provider scope, `forwardRef`, or mutable state on an
  injectable/module singleton. Cluster: singleton-vs-request scope and **upward
  scope propagation**; mutable singleton state as a cross-tenant bleed vector;
  module cycle-breaking via a smaller exposed surface vs `forwardRef` masking.
- `di-backend-layering.md` *(TODO — create)*
  Read when the diff touches resolver/controller/handler/repository boundaries.
  Cluster: thin adapter over coordinating handler; handler-never-calls-handler
  (shared side-effects → a reused provider); resolver-never-touches-persistence;
  repository is data-access only.
- `di-backend-authorization.md` *(TODO — create)*
  Read when the diff touches permission checks or cross-module access. Cluster:
  ask-don't-tell permission providers; single early auth choke point enforced
  across every entry point (GraphQL/REST/queue); no inline/static permission
  checks.

## How to use it

- Treat findings from this add-on as `architecture`/`reliability`/`security`
  findings — fold them into those specialists' registers, do not create a
  parallel lane.
- Every finding must name a concrete, reachable failure: state leaked across
  tenants/requests, a boot-time circular-dependency crash, a double or skipped
  authorization, a resolver reaching persistence and bypassing coordination.
  These bugs surface under concurrency or from a non-GraphQL entry point — states
  a single-request manual test misses. Describe that state explicitly.
