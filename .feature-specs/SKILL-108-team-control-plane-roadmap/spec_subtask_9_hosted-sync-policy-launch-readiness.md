# SKILL-108 Subtask 9: Hosted Sync Policy and Launch Readiness

## Scope

Complete the roadmap by connecting hosted registry/policy contracts to client
sync behavior and documenting the launch path. This subtask should preserve the
local-first model: hosted sync is an extension of `team sync`, not a replacement
for local bundle files or local registries.

Implement or prepare:

- hosted registry lookup for approved bundle versions by org/team/channel
- policy-controlled auto-sync or prompted sync
- user-visible sync decisions: current bundle, available bundle, required vs
  optional update, channel, policy reason, and rollback target
- audit emission or client-side audit request for sync, publish, rollback, and
  telemetry setting changes where hosted mode is enabled
- customer-managed telemetry proxy settings in hosted team/org policy
- docs and launch materials that separate local-first Phase 1-3 from hosted
  Phase 4
- discovery checklist instrumentation or docs for real-user proof before broad
  hosted rollout

## Acceptance Criteria

1. Hosted-mode clients can discover the approved bundle for a team/channel and
   feed it through the same verification and install path as local `team sync`.
2. Sync policy can require auto-sync, prompt the user, or disable hosted sync;
   client output explains the policy reason and selected channel.
3. Hosted sync preserves local rollback behavior and reports rollback state
   consistently with local bundle sync.
4. Hosted policy can point telemetry to a customer-managed proxy without
   overriding a user's explicit telemetry opt-out.
5. Hosted audit requests or records are generated for sync, publish, rollback,
   permission, telemetry setting, and policy changes when hosted mode is active.
6. Local-first bundle export/sync continues to work with no hosted config and no
   network dependency.
7. Docs cover local-first rollout, hosted rollout prerequisites, privacy tiers,
   self-hosted telemetry proxy, no customer-code hosting, no desktop Git
   publishing, and the discovery checklist.
8. End-to-end smoke covers local bundle sync, hosted registry lookup via fake
   client, prompted sync, auto-sync, rollback, and telemetry-proxy policy.

## Non-goals

- No production SaaS deployment.
- No billing implementation.
- No automatic skill mutation.
- No customer code hosting.
- No requirement that all teams adopt hosted mode.

## Dependency Notes

Depends on subtask 3 for sync/rollback behavior, subtask 7 for telemetry
analysis expectations, and subtask 8 for hosted org/registry/audit contracts.

## Validation Strategy

Run hosted-client, sync, CLI, desktop, and docs checks plus:

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
```

Manual smoke: configure a fake hosted registry client, discover a team bundle,
prompt or auto-sync according to policy, run a basic workflow, rollback, and
confirm local-only sync still works offline.

## Next Path

After this subtask completes, run the full parent validation gate and update
`docs/team-control-plane-roadmap.md` with implementation status and any
discovery findings collected during rollout.
