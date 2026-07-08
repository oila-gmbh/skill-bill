# SKILL-108 Subtask 5: Local Roles and Proposal Workflow

## Scope

Represent the Phase 2 role model in local-first form without pretending to have
hosted identity or remote enforcement. This subtask defines team workspace
metadata, local role intent, proposal records, and publish gates that are honest
about what can be enforced locally.

Roles:

- Team Admin: edit, validate, publish, sync, and rollback team bundles
- Maintainer: edit or propose team source changes, but publish only when local
  policy permits
- Member: sync and use published setup
- Viewer: read docs, bundle metadata, validation status, and telemetry summaries

Implement:

- team workspace metadata carrying role/policy declarations
- local proposal records for maintainer-authored changes
- CLI/desktop display of current role, allowed actions, and blocked actions
- publish gates that require Team Admin role when local policy enables role
  gating
- clear warnings that local role gates are policy controls, not hosted identity
  security, until hosted org work lands
- proposal export/import shape that hosted work can later adopt

## Acceptance Criteria

1. Team workspace metadata can declare local roles, publish policy, proposal
   destination, and viewer/member/admin capabilities without breaking existing
   single-user installs.
2. CLI and desktop surfaces display the current local role and disable or block
   publish/rollback actions when local policy says the role cannot perform them.
3. Maintainer proposal flow writes a governed proposal artifact containing
   changed source hashes, validation result, target channel, author label, and
   intended bundle version.
4. Team Admin can accept a local proposal and publish a bundle using the same
   export service from subtask 2.
5. Member and Viewer modes cannot publish from desktop or CLI when local policy
   gates publish actions.
6. Error messages state the difference between local policy gating and hosted
   authenticated authorization.
7. Tests cover role parsing, unknown role rejection, policy-disabled publish,
   proposal creation, proposal acceptance, and backwards compatibility when no
   team role metadata exists.

## Non-goals

- No hosted users, memberships, or authentication.
- No cryptographic signing requirement unless it is already available locally.
- No billing or license enforcement.
- No cross-team policy inheritance.
- No hosted proposal review workflow.

## Dependency Notes

Depends on subtask 1 for bundle/team metadata and subtask 4 for desktop publish
surfaces. Hosted role enforcement is deferred to subtask 8.

## Validation Strategy

Run focused domain, CLI, and desktop tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-cli:test :runtime-desktop:feature:skillbill:jvmTest)
npx --yes agnix --strict .
```

Manual smoke: set local role to Maintainer, create a proposal, verify publish
is blocked, switch to Team Admin, accept and publish.

## Next Path

After this lands, run subtask 6 to attach team and bundle identity to telemetry
events while preserving privacy levels.
