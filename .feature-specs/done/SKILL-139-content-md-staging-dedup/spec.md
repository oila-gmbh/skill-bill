# SKILL-139: stop shipping a redundant content.md in installed skill staging

Status: Draft

## Problem

Install writes the authored `content.md` prose into a listed skill's installed
directory twice:

- Since SKILL-41, the `SKILL.md` renderer inlines the full authored body into
  the generated `## Execution` section.
- Install separately copies `content.md` verbatim into the same staging dir
  (`~/.skill-bill/installed-skills/<slug>-<hash>/`) via
  `authoredFilesFor` + `copyAuthoredIntoStaging`.

No Kotlin runtime code reads the staged copy. Every `content.md` reader
resolves against repo SOURCE paths: authoring CLI (`show`, `explain`, `render`,
`validate`, `fill`, `edit`), pack loading, repo validation, review rubrics,
native-agent `compose: governed-content`, and the install content hash all read
the source tree. The staging reuse/drift check (`isReusableInstallStaging`)
reads only `.content-hash` and existence-checks `SKILL.md`, pointers, and
sidecars; `content.md` is not even in its integrity set.

The only consumer of the staged sibling copy is an agent-prose directive in
`orchestration/shell-content-contract/shell-ceremony.md`:

> When a sibling `content.md` exists, treat it as the authored execution body
> for the current skill.

Because `SKILL.md` already inlines that body, a compliant agent that follows the
ceremony line loads the same execution prose twice at invocation. The staged
`content.md` therefore serves no runtime purpose except to enable a redundant
read.

## Goal

Make the rendered `SKILL.md` `## Execution` body the single authored-body
surface an agent loads for a listed skill. Stop staging the redundant
`content.md` copy, and remove the ceremony directive that points agents at a
sibling `content.md`. Keep source `content.md` as the pure authored source and
keep it in the install content hash so drift detection is unchanged.

This is coherent with SKILL-41 (inline the body into `SKILL.md`) and does not
reintroduce the rejected SKILL-31 thin-pointer shape.

## Scope

### In-scope changes

1. **Staging copy**
   - Exclude `content.md` from the authored files copied into a listed
     (content-managed) skill's installed staging dir.
   - Keep source `content.md` in the install content-hash inputs. The copy set
     and the hash set must diverge: `computeInstallContentHash` continues to
     read source `content.md`, while `copyAuthoredIntoStaging` no longer emits
     it. Do not drop it from both by naively excluding it in `authoredFilesFor`.
   - Keep `.content-hash`, `SKILL.md`, generated pointers, and `native-agents/`
     staging unchanged.

2. **Internal sidecar audit**
   - Determine whether internal-child skills contribute a redundant verbatim
     `content.md` into a parent's staging dir. Remove any such redundant copy.
   - Preserve PD6: each rendered `<skill-name>.md` sidecar wrapper stays full and
     self-contained. Only a redundant `content.md` copy (if present) is dropped;
     the sidecar wrapper format is not trimmed.

3. **Ceremony contract**
   - Remove the "treat a sibling `content.md` as the authored execution body"
     directive from `orchestration/shell-content-contract/shell-ceremony.md`.
   - Ensure no rendered ceremony pointer or support pointer still tells agents to
     read a sibling `content.md` as a body source.

4. **Docs and policy**
   - Update `docs/skill-source-generation.md` install-staging contents list so it
     no longer lists `content.md` among staged files.
   - Update `AGENTS.md` and shell-content-contract docs wherever they imply the
     staged `content.md` is a runtime body source.

5. **Tests and fixtures**
   - Update staging tests that assert `content.md` is copied verbatim into
     staging, plus reuse/reconcile expectations and render/staging snapshots.
   - Add coverage proving a listed skill's staging dir does not contain
     `content.md` while `SKILL.md` still carries the inlined `## Execution` body.
   - Add coverage proving the install content hash still incorporates source
     `content.md` and that editing source `content.md` re-stages the skill.
   - Add internal-sidecar coverage proving no redundant `content.md` is staged
     and that each rendered sidecar wrapper remains self-contained.

## Acceptance Criteria

1. After install, a listed skill's staging dir contains `SKILL.md`,
   `.content-hash`, generated pointers, and any `native-agents/` output, but not
   a verbatim `content.md` copy.
2. Rendered `SKILL.md` still contains the full inlined `## Execution` body; the
   SKILL-41 wrapper shape is unchanged.
3. The install content hash still incorporates source `content.md`: editing
   source `content.md` changes the hash and forces a re-stage; drift detection is
   unaffected.
4. Internal sidecar staging carries no redundant verbatim `content.md` copy, and
   every rendered `<skill-name>.md` sidecar wrapper remains full and
   self-contained (PD6 parity preserved).
5. `shell-ceremony.md` no longer instructs agents to read a sibling `content.md`
   as the authored body, and no rendered ceremony/support pointer references a
   sibling `content.md` as a body source.
6. `skill-bill show`, `explain`, `render`, `validate`, `fill`, `edit`, and
   native-agent composition continue to read source `content.md` and behave
   identically.
7. `docs/skill-source-generation.md` and `AGENTS.md` state that listed-skill
   staging no longer ships `content.md`.
8. `(cd runtime-kotlin && ./gradlew check)` passes.
9. `npx --yes agnix --strict .` passes.
10. `scripts/validate_agent_configs` passes.

## Non-goals

- Do not revert SKILL-41 or reintroduce a thin
  `Follow the instructions in [content.md](content.md).` execution pointer
  (the rejected SKILL-31 shape).
- Do not change source `content.md` files or the authored-source contract.
- Do not change native-agent install locations or composition semantics.
- Do not trim the rendered internal sidecar wrapper format; PD6 self-containment
  stays.
- Do not change install/pack selection scope.

## Design notes

The core seam is that `authoredFilesFor` currently feeds both the staging copy
and the content-hash inputs. The refactor must split those responsibilities so
source `content.md` keeps contributing to the hash while it stops being copied
into staging. Options: add a copy-time exclusion in `copyAuthoredIntoStaging`
for content-managed skills, or compute a separate "files to stage" set distinct
from "files to hash". Prefer the option that keeps hash inputs byte-identical to
today so no unrelated staging hashes churn.

`isContentManagedSkill` and `resolveStagedSymlinkTarget` key off the source
`content.md`, so removing the staged copy does not affect symlink-target
resolution or content-managed classification.

## Validation

```bash
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Manual checks:

```bash
skill-bill install apply --platform-mode selected --platform kotlin ...
ls ~/.skill-bill/installed-skills/bill-code-review-*/   # no content.md; SKILL.md present
```

## Recommended next prompt

Run `skill-bill goal SKILL-139`.
