---
status: Complete
parent_spec: ./spec.md
subtask_id: 1
---

# SKILL-76 · Subtask 1 — Copy-in + repoint foundation (clone-deletable install)

Parent overview: [spec.md](./spec.md). This subtask owns ONLY the foundation:
copy the authored source closure into `~/.skill-bill`, repoint the runtime at the
copy, and exempt that copy from the pre-install wipe so the later reconciliation
subtask has something durable to reconcile against. No baseline manifest, no
reconciliation policy, no interactive prompt here.

## Scope

1. **Copy-in (AC-1).** In `install.sh`, before `apply_runtime_install` and before
   any skill linking, copy the authored source closure into `$SKILL_BILL_STATE_DIR`
   using the EXISTING atomic copy idiom (mirror `install_packaged_runtime_distribution`,
   `install.sh:636-652`: `rm -rf $tmp; cp -R $src $tmp; rm -rf $target; mv $tmp $target`).
   Copy the FULL closure the runtime plan/apply reads relative to `--repo-root`:
   - `skills/` → `$SKILL_BILL_STATE_DIR/skills`
   - `platform-packs/` → `$SKILL_BILL_STATE_DIR/platform-packs`
   - the WHOLE `orchestration/` tree → `$SKILL_BILL_STATE_DIR/orchestration`
     (safest closure: it carries `skill-classes/*.yaml` read by `SkillClassLoader`
     plus every `PLAYBOOK.md`/`specialist-contract.md`/`shell-ceremony.md` and the
     `platform-packs/kmp/addons/android-*.md` targets enumerated in
     `ScaffoldSupport.supportingFileTargets(repoRoot)` — these are INLINED at render
     time and MUST exist as REAL files under the copy).
   Copy `content.md` source only — NEVER generated `SKILL.md` wrappers or support
   pointers (those are render OUTPUT produced into the staging cache; read
   `docs/skill-source-generation.md` before touching staging/rendering).
   Targets must be REAL files, not symlinks back to the clone, because
   `InstallPlanBuilder.validatePointerInputs` require()s every pointer target
   `startsWith(repoRoot)` via real-path containment.
   `orchestration/contracts/*.yaml` is NOT needed (loaded as classpath resources).

2. **Repoint wiring (AC-2 / AC-4).** Change `build_runtime_install_args`
   (`install.sh:1778-1799`) so the three source args point at the copy:
   `--repo-root "$SKILL_BILL_STATE_DIR"`, `--skills "$SKILL_BILL_STATE_DIR/skills"`,
   `--platform-packs "$SKILL_BILL_STATE_DIR/platform-packs"` (replacing
   `$PLUGIN_DIR` / `$SKILLS_DIR` / `$PLATFORM_PACKS_DIR`). This single repoint is
   the ENTIRE mechanism for AC-2 and AC-4: `InstallStaging.resolveStagedSymlinkTarget`
   already returns the staging dir (content-managed) or the resolved source dir
   (non-content-managed fallback) relative to whatever repoRoot it is given — DO NOT
   fork `InstallPrimitives.installSkill` or any Kotlin source/target-resolution code.
   Verify this holds; if any Kotlin-side path is found assuming the clone, fix it
   minimally and note it.

3. **Pre-install wipe exemption (BLOCKER — enables AC-3).** `run_pre_install_uninstall`
   (`install.sh:610-625`) execs `uninstall.sh`, which does `rm -rf $SKILL_BILL_STATE_DIR`
   (`uninstall.sh:632-635`), wiping the entire `~/.skill-bill` on EVERY install —
   which would delete the copied source (and, later, the baseline). Choose and
   implement an approach that PRESERVES the copied source tree (`skills/`,
   `platform-packs/`, `orchestration/`) and reserves space for a future baseline
   manifest file across the pre-INSTALL wipe, while still wiping the runtime
   binaries + installed-skills staging cache + persistent state DBs so generator
   changes still land. Recommended: scope the pre-install cleanup so it does NOT
   remove the editable source subtree (exempt those paths), OR snapshot+restore
   them around the wipe. CRITICAL CONSTRAINT: an EXPLICIT `./uninstall.sh` must
   STILL fully remove `~/.skill-bill` — only the pre-INSTALL path preserves the
   editable source. Keep the `SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1` test opt-out
   working. Leave a clearly-named seam/marker the reconciliation subtask can build
   on (the spot where the copy lands and where a baseline manifest will live).

4. **Migration smoke (partial AC-10).** Confirm copy-in + repoint + the existing
   `--replace-existing-skill-bill-links` flag (already passed at `install.sh:1791`)
   repoints managed agent symlinks to the copy. Full AC-10 dangling-link assertions
   live in subtask 3; here just ensure nothing in the foundation reintroduces
   clone-pointing targets.

## Acceptance Criteria (this subtask)

- AC-1: `./install.sh` copies `skills/` + `platform-packs/` + `orchestration/`
  (the full plan/apply read closure) into `~/.skill-bill` as real files BEFORE
  skill linking runs.
- AC-2: runtime receives `--repo-root` (+ `--skills`/`--platform-packs`) pointing
  at the copy; all staging/symlink targets resolve against the copy.
- AC-4: non-content-managed skills fall back to a direct symlink into the COPY
  under `~/.skill-bill`, never the clone.
- AC-3 (foundation portion): after a successful install, deleting the clone leaves
  a fully functional install — every skill (content-managed + non-content-managed)
  still resolves. (The "reinstall without clone still succeeds + preserves edits"
  portion is completed in subtask 2.)
- Pre-install wipe no longer destroys the copied source tree; explicit uninstall
  still fully removes `~/.skill-bill`.

## Validation (this subtask)

- `bill-code-check` (routes Kotlin/Gradle).
- `cd runtime-kotlin && ./gradlew check`.
- New/updated tests:
  - `runtime-infra-fs` (`InstallApplyTest` / `InstallStagingTest`, reusing
    `InstallApplyTestSupport.setupApplyFixture`): assert content-managed staging
    targets AND non-content-managed fallback symlinks resolve UNDER the copied
    repoRoot (temp `~/.skill-bill`), not the clone.
  - `runtime-core` `InstallerShellDelegationTest` (real `install.sh`/`uninstall.sh`
    over `SKILL_BILL_RELEASE_DIR` fixtures, `SKILL_BILL_SKIP_PREINSTALL_UNINSTALL`
    where needed): assert copy-in populates `~/.skill-bill/{skills,platform-packs,
    orchestration}`; argv carries `--repo-root $SKILL_BILL_STATE_DIR`; delete the
    clone after install and assert skills still resolve; assert the pre-install
    cleanup preserves the copied source subtree while still clearing runtime/cache.
- `npx --yes agnix --strict .`; `scripts/validate_agent_configs`.

## Non-goals (this subtask)

- Baseline manifest, content-hash reconciliation, adopt/keep-local/conflict policy
  (subtask 2).
- Interactive both-changed prompt / no-TTY abort / summary reporting (subtask 2).
- Full AC-10 dangling-link migration assertions + SKILL-74/75 parity tests
  (subtask 3).
- Portable export/import, push-upstream, versioned registry, three-way merge,
  desktop re-pointing, `skill-bill refresh`.

## Dependencies

None. This is the verified foundation subtasks 2 and 3 build on.

## Handoff

Run `bill-feature-task` on
`.feature-specs/SKILL-76-self-contained-skill-source/spec_subtask_1_copyin-repoint-foundation.md`.
