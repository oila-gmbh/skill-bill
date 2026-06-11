# core/data — history

## [2026-06-10] SKILL-77 git provisioning and graceful degradation (subtask 3)
Areas: runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing
- JvmInstalledWorkspaceGitProvisioner: provisions git for ~/.skill-bill via git rev-parse --show-toplevel detection (AlreadyProvisioned if own root; init if not), writes scoped .gitignore, stages skills/ + platform-packs/, creates initial commit. Returns ProvisionResult sealed class. @Inject @SingleIn(UserScope::class), bound via @Provides in JvmDataBindings.
- KSP/ABI seam: `internal var runnerFactory: (ProcessBuilder) -> Process` (not a constructor lambda); tests replace it to simulate a missing binary at any provisioning step. reusable
- git subprocess hardening: GIT_PROVISIONER_HARDENING_FLAGS (--literal-pathspecs, --no-optional-locks, -c core.fsmonitor=, hooksPath=/dev/null, pager=, sshCommand=, protocol.file.allow=user) + GIT_PROVISIONER_ENV_VARS_TO_REMOVE + GIT_TERMINAL_PROMPT=0. Constants duplicated from RuntimeGitGateway to respect KSP/ABI boundary. reusable
- ByteArrayOutputStream thread-safety: synchronized writes in drainCapped (synchronized(sink)), synchronized read after join (synchronized(capturedBytes)), isAlive check + interrupt() + 200ms final join after 1s primary join timeout. reusable
- JvmInstalledWorkspaceLocator binding also added to JvmDataBindings in this subtask (@Provides bindInstalledWorkspaceLocator).
Feature flag: N/A
Acceptance criteria: 6/6 implemented
