# core/domain — history

## [2026-06-10] SKILL-77 git provisioning and graceful degradation (subtask 3)
Areas: runtime-desktop/core/domain
- New domain interface InstalledWorkspaceGitProvisioner (fun provision(workspaceRoot: String): ProvisionResult) + sealed class ProvisionResult (Provisioned, AlreadyProvisioned, GitUnavailable(errorMessage), Failed(errorMessage)).
- ProvisionResult is the typed domain contract for the provisioning surface; callers pattern-match without strings. JVM impl in core/data, fake in core/testing. reusable
Feature flag: N/A
Acceptance criteria: 6/6 implemented
