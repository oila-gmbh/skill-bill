# core/data — history

## [2026-07-04] SKILL-101 desktop external add-ons in nav tree
Areas: runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/feature/skillbill
- Desktop repo browsing now resolves external_addon_sources via ExternalAddonOverlayService through a testable resolver seam; core/data does not parse config directly.
- SkillTreeBuilder merges top-level external source .md files into the existing Add-ons platform groups, marks them editable/external, targets the source path for authored content, and keeps pack-owned rows when external resolution fails.
- Dedup rule: external (platform, slug) wins over an overlaid pack-owned duplicate, so installed workspace browsing shows one editable source-of-truth row. reusable
- Domain tree items carry external provenance and the SkillBill nav row renders the EXT badge only from that flag.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-06-11] SKILL-79 git/validate/render gateway impls deleted
Areas: runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing
- Deleted RuntimeGitGateway (~1564 lines), RuntimePrPublishingGateway, JvmInstalledWorkspaceGitProvisioner, mapper/ValidationSummaryMapper, and their tests; dropped all four gateway/provisioner @Provides bindings from JvmDataBindings and the matching fakes from core/testing FakeSkillBillServices (FakeInstalledWorkspaceGitProvisioner removed).
- RuntimeRepoBrowserService no longer takes a `validator: (Path) -> RepoValidationReport` seam; it calls repoValidationService.validateRepo(root) directly. The git/validate provisioning hardening constants documented in the SKILL-77 entry below went with RuntimeGitGateway.
- Cross-module guard: runtime-core RuntimeDesktopGatewayPolicyTest whitelists desktop mappers that must exist — when you delete a mapper here, update that whitelist or its test fails. reusable
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-06-10] SKILL-77 git provisioning and graceful degradation (subtask 3)
Areas: runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing
- JvmInstalledWorkspaceGitProvisioner: provisions git for ~/.skill-bill via git rev-parse --show-toplevel detection (AlreadyProvisioned if own root; init if not), writes scoped .gitignore, stages skills/ + platform-packs/, creates initial commit. Returns ProvisionResult sealed class. @Inject @SingleIn(UserScope::class), bound via @Provides in JvmDataBindings.
- KSP/ABI seam: `internal var runnerFactory: (ProcessBuilder) -> Process` (not a constructor lambda); tests replace it to simulate a missing binary at any provisioning step. reusable
- git subprocess hardening: GIT_PROVISIONER_HARDENING_FLAGS (--literal-pathspecs, --no-optional-locks, -c core.fsmonitor=, hooksPath=/dev/null, pager=, sshCommand=, protocol.file.allow=user) + GIT_PROVISIONER_ENV_VARS_TO_REMOVE + GIT_TERMINAL_PROMPT=0. Constants duplicated from RuntimeGitGateway to respect KSP/ABI boundary. reusable
- ByteArrayOutputStream thread-safety: synchronized writes in drainCapped (synchronized(sink)), synchronized read after join (synchronized(capturedBytes)), isAlive check + interrupt() + 200ms final join after 1s primary join timeout. reusable
- JvmInstalledWorkspaceLocator binding also added to JvmDataBindings in this subtask (@Provides bindInstalledWorkspaceLocator).
Feature flag: N/A
Acceptance criteria: 6/6 implemented
