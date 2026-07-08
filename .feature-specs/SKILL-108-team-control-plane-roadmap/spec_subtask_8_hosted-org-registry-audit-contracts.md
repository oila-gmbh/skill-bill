# SKILL-108 Subtask 8: Hosted Org, Registry, and Audit Contracts

## Scope

Define the hosted control-plane contracts for Phase 4 without replacing the
local-first implementation. This subtask introduces domain models, API/client
contracts, and persistence boundaries for organizations, teams, users,
memberships, roles, hosted bundle registry, sync policy, telemetry settings, and
audit logs.

Contracts should cover:

- organizations
- teams
- users
- memberships
- roles: Org Owner, Team Admin, Maintainer, Member, Viewer
- bundle registry entries
- channels: stable, beta, development
- policy-controlled auto-sync or prompted sync
- audit log entries for publish, rollback, permission changes, sync policy
  changes, telemetry setting changes, and registry mutations
- customer-managed telemetry proxy configuration

The hosted registry stores governed Skill Bill bundles and metadata. It must not
host customer code repositories or require any one coding agent.

## Acceptance Criteria

1. Hosted domain/API contracts model organizations, teams, users, memberships,
   roles, bundle registry entries, channels, sync policy, telemetry settings,
   and audit entries.
2. Role permissions distinguish Org Owner, Team Admin, Maintainer, Member, and
   Viewer according to the roadmap.
3. Hosted bundle registry contracts reuse the local team-bundle schema rather
   than defining a parallel bundle format.
4. Audit log contract records actor, action, target, organization/team, bundle
   identity, timestamp, and privacy-safe details for publish, rollback,
   permission, sync-policy, telemetry-setting, and registry events.
5. Client-side contract models can be consumed by CLI/desktop without requiring
   hosted mode for local-first operation.
6. Contracts explicitly forbid hosting customer code and do not assume a single
   coding agent.
7. Tests cover role permission matrices, invalid channel rejection, audit entry
   serialization, local-bundle schema reuse, and backwards compatibility with
   local-only installs.
8. Docs explain hosted contracts as a later extension surface, not a
   prerequisite for Phase 1-3 local-first adoption.

## Non-goals

- No production hosted service deployment.
- No billing.
- No cross-team policy inheritance unless explicitly represented as inert
  future metadata.
- No customer source-code hosting.
- No hosted dashboard implementation.

## Dependency Notes

Depends on subtask 1 for the bundle format, subtask 5 for role semantics, and
subtask 6 for telemetry privacy/attribution fields.

## Validation Strategy

Run domain and contract tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-application:test)
npx --yes agnix --strict .
```

If a new runtime contract YAML is introduced, include the schema copy task,
contract-version parity test, and typed invalid-schema error coverage.

## Next Path

After this lands, run subtask 9 to connect hosted policy and registry contracts
to sync behavior and launch-readiness docs.
