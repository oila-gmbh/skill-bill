# core/domain — history

## [2026-06-11] SKILL-79 git/validate/render domain surfaces removed
Areas: runtime-desktop/core/domain
- Deleted: InstalledWorkspaceGitProvisioner + ProvisionResult, and from SkillBillServices/SkillBillModels the GitGateway, ValidationGateway, RenderGateway, PrPublishing surfaces plus models GitPublishingStatus, PublishLink/PublishLinkKind, ValidationSummary, RenderSummary, PostPublishReinstall*. These were the SKILL-77 provisioning contracts — they no longer exist.
- RepoSession narrowed to (repoPath, isRecognizedSkillBillRepo, loadStatus): the domain session carries no git working-tree state. Producers/consumers all agree on this shape. reusable
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-06-10] SKILL-77 git provisioning and graceful degradation (subtask 3)
Areas: runtime-desktop/core/domain
- New domain interface InstalledWorkspaceGitProvisioner (fun provision(workspaceRoot: String): ProvisionResult) + sealed class ProvisionResult (Provisioned, AlreadyProvisioned, GitUnavailable(errorMessage), Failed(errorMessage)).
- ProvisionResult is the typed domain contract for the provisioning surface; callers pattern-match without strings. JVM impl in core/data, fake in core/testing. reusable
Feature flag: N/A
Acceptance criteria: 6/6 implemented
