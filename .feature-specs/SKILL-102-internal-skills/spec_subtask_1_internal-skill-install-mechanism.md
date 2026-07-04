---
status: In Progress
---

# SKILL-102 Subtask 1 - Internal-Skill Install Mechanism

Parent spec: [.feature-specs/SKILL-102-internal-skills/spec.md](./spec.md)
Issue key: SKILL-102

Read the parent spec's **Pinned Decisions** section first. PD1, PD2, PD5, and
PD6 are binding here. Do not redesign them.

## Scope

Add the internal-skill capability to the authoring/install pipeline in
`runtime-kotlin`, fully inert until a skill opts in. **No skill in the repo is
classified internal in this subtask**; the mechanism ships with zero change to
installed output.

### Frontmatter contract (PD1)

An internal skill's `content.md` frontmatter gains exactly one new optional
key:

```yaml
---
name: bill-feature-task
description: "..."
internal-for: bill-feature
---
```

Rules, all enforced with typed, actionable loud-fail errors:

- `internal-for` value must be the `name` of another discovered skill.
- The parent must not itself carry `internal-for` (no chains, depth is 1).
- The parent must not be the skill itself.
- A missing or empty value is an error, not "treat as listed".
- Error messages must name the offending skill, the declared parent, and the
  rule violated (e.g. `internal skill 'bill-feature-task' declares parent
  'bill-featur' which is not a discovered skill`).

### Touch points (exact files)

Work happens in these files plus their tests; if you believe another file
must change, verify why before touching it:

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/authoring/AuthoringDiscovery.kt`
  — parse `internal-for` during discovery; classify each target as listed or
  internal; enforce the frontmatter rules above.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/plan/InstallPlanSkillDiscovery.kt`
  — install-plan discovery must carry the same classification. The existing
  hard-fail "every `skills/bill-*/` dir must contain `content.md`" stays: an
  internal skill still has a `content.md`.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/staging/InstallStaging.kt`
  and
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/staging/InstallStagingIO.kt`
  — internal skills get no staged skill directory of their own. Instead,
  render the same governed wrapper `renderWrapper` produces (see
  `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/authoring/AuthoringRender.kt`)
  into the parent's staged directory as `<skill-name>.md` (PD2, PD6).
- Agent linking: whatever plans per-agent `skills_dir` links must skip
  internal skills entirely — the sidecar travels inside the parent's
  directory, so no separate link exists for any agent in `config.yaml`.
- Uninstall / reinstall / durable install overlay / `skill-bill doctor` —
  internal sidecars are generated artifacts owned by the parent skill's
  install unit: uninstalling the parent removes them, reinstall re-renders
  them, doctor treats a healthy install containing them as clean.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/RepoValidationRuntime.kt`
  (and whatever backs `skill-bill validate`) — accept the new frontmatter key
  and enforce the same loud-fail rules at validation time.

### Collision guard

Loud-fail at staging time if the parent's source directory already contains
an authored file whose name equals a would-be sidecar name (e.g. an authored
`bill-feature/bill-feature-task.md`). Follow the existing generated-artifact
guard pattern
(`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/pointer/GeneratedArtifactGuard.kt`).

### Native-agent parity

An internal skill directory that hosts `native-agents/` must go through the
existing native-agent composition/install path with zero changes. Native
agents already install outside the skills dir; classification must not
affect them.

### Explicitly out of scope for this subtask

- Do NOT add `internal-for` to any `content.md` in `skills/`.
- Do NOT edit any skill prose, description, or call site.
- Do NOT touch `WorkflowEngine.kt`, telemetry, MCP, or DB code.
- Do NOT change native-agent composition or sidecar-inlining logic
  (`NativeAgentSidecarInlining.kt` is a different mechanism; leave it alone).

## Acceptance Criteria

1. The `internal-for` frontmatter key exists per PD1, parsed in both
   authoring discovery and install-plan discovery.
2. Each loud-fail rule (unknown parent, internal parent, self parent,
   missing/empty value, sidecar name collision) has a typed error, an
   actionable message naming the offending skill and rule, and a unit test.
3. Install stages an internal skill as a rendered `<skill-name>.md` sidecar
   at the top level of the parent's staged skill directory, with the governed
   wrapper (descriptor, class sections, execution body, ceremony) intact, and
   creates no standalone staged skill directory for it.
4. No agent `skills_dir` receives a link or directory for an internal skill;
   the parent skill's installed directory contains the sidecar on every agent
   configured in `config.yaml`.
5. A `native-agents/` bundle hosted in an internal skill's source directory
   installs exactly as it does for a listed skill (covered by a test using a
   fixture skill, not by migrating a real one).
6. Uninstall removes internal sidecars with the parent skill; reinstall and
   the durable install overlay re-render them; `skill-bill doctor` reports no
   findings on a healthy install containing internal skills; repeated
   installs are idempotent.
7. With no repo skill classified internal, the staged install output is
   byte-identical to the output before this change (verified by diffing the
   staged trees, not by inspection).
8. Unit tests cover classification parsing, each loud-fail case, staging
   layout and naming, agent-link planning, native-agent parity, and
   install/uninstall idempotency — using fixture skills created inside the
   tests, not repo skills.

## Non-Goals

- Everything in "Explicitly out of scope" above.
- No CLI surface changes (`skill-bill list` or similar) in this subtask.

## Dependency Notes

No dependencies. Must land before subtask 2, which is the first consumer.

## Validation Strategy

- `(cd runtime-kotlin && ./gradlew check)` with the new unit tests.
- Byte-identical staged-output check for criterion 7: run an install to a
  scratch prefix from `main` and from this commit, then `diff -r` the two
  staged trees; expect no differences.
- `skill-bill validate` against the unchanged repo: expect pass.

## Next Path

Proceed to
[Subtask 2 - Feature-Execution Family Migration](./spec_subtask_2_feature-execution-family-migration.md).
