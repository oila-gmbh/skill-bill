package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.GitPublishingStatus
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.InstalledWorkspaceAvailability
import skillbill.desktop.core.domain.model.ProvisionResult
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeDesktopPreferenceStore
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakePrPublishingGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRenderGateway
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import skillbill.desktop.core.testing.install.FakeDesktopFirstRunGateway
import skillbill.desktop.core.testing.workspace.FakeInstalledWorkspaceGitProvisioner
import skillbill.desktop.core.testing.workspace.FakeInstalledWorkspaceLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the push-affordance guard (AC5) and provisioner integration (AC4) in
 * [SkillBillViewModel].
 */
class SkillBillViewModelInstalledWorkspaceGitTest {

  // AC5: when the installed workspace is open and there is no remote, pushStatusErrorMessage must
  // be null so the push surface is hidden without surfacing a misleading error.
  @Test
  fun `pushStatusErrorMessage is null for installed workspace with no push target`() {
    val installedRoot = "/home/user/.skill-bill"
    val gitGateway = FakeGitGateway(
      scriptedPublishingStatus = GitPublishingStatus(
        pushTarget = null,
        errorMessage = "No Git remotes are configured.",
      ),
    )
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = installedRoot, availability = true),
    )
    val viewModel = newViewModel(
      gitGateway = gitGateway,
      installedWorkspaceLocator = locator,
      recentRepoRepository = FakeRecentRepoRepository(),
    )
    // The VM auto-opens the installed workspace on init, so the session is already loaded.
    val request = viewModel.beginGitRefresh()
    val result = viewModel.runGitRefresh(request)
    val state = viewModel.finishGitRefresh(result)

    // F-MAJOR-5: precondition — the session must be the installed workspace root so the
    // pushStatusErrorMessage suppression logic is actually exercised.
    assertEquals(
      installedRoot,
      state.selectedRepoPath,
      "Precondition: session must be the installed workspace root",
    )
    // pushTarget is null (no remotes) for the installed workspace root.
    assertNull(state.pushTarget, "pushTarget must be null when no remotes are configured")
    // pushStatusErrorMessage must also be null so no error is shown to the user.
    assertNull(
      state.pushStatusErrorMessage,
      "pushStatusErrorMessage must be null for installed workspace with no remote",
    )
  }

  // AC5 (contrast): when the repo is NOT the installed workspace root and there is no remote,
  // pushStatusErrorMessage passes through from GitPublishingStatus.
  @Test
  fun `pushStatusErrorMessage passes through for non-installed workspace with no push target`() {
    val installedRoot = "/home/user/.skill-bill"
    val gitGateway = FakeGitGateway(
      scriptedPublishingStatus = GitPublishingStatus(
        pushTarget = null,
        errorMessage = "No Git remotes are configured.",
      ),
    )
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = installedRoot, availability = true),
    )
    val viewModel = newViewModel(
      gitGateway = gitGateway,
      installedWorkspaceLocator = locator,
      recentRepoRepository = FakeRecentRepoRepository(),
    )
    // Open a DIFFERENT repo — not the installed workspace.
    viewModel.selectRepoPath("/some/other/repo")
    val request = viewModel.beginGitRefresh()
    val result = viewModel.runGitRefresh(request)
    val state = viewModel.finishGitRefresh(result)

    assertNull(state.pushTarget)
    assertEquals(
      "No Git remotes are configured.",
      state.pushStatusErrorMessage,
      "pushStatusErrorMessage must pass through for a non-installed-workspace repo",
    )
  }

  // AC5: when the installed workspace has a remote, push works normally and pushStatusErrorMessage
  // passes through (it will be null, since there is a valid pushTarget).
  @Test
  fun `push affordance present when installed workspace has a remote`() {
    val installedRoot = "/home/user/.skill-bill"
    val pushTarget = GitPushTarget(
      remoteName = "origin",
      branchName = "main",
      expectedCurrentBranch = "main",
      displayName = "origin/main",
      isLikelyCanonical = false,
    )
    val gitGateway = FakeGitGateway(
      scriptedPublishingStatus = GitPublishingStatus(pushTarget = pushTarget),
    )
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = installedRoot, availability = true),
    )
    val viewModel = newViewModel(
      gitGateway = gitGateway,
      installedWorkspaceLocator = locator,
      recentRepoRepository = FakeRecentRepoRepository(),
    )
    val request = viewModel.beginGitRefresh()
    val result = viewModel.runGitRefresh(request)
    val state = viewModel.finishGitRefresh(result)

    assertEquals(pushTarget, state.pushTarget, "pushTarget must be present when remote exists")
    assertNull(state.pushStatusErrorMessage, "pushStatusErrorMessage must be null when pushTarget is set")
  }

  // AC4: when git is unavailable, the provisioner returns GitUnavailable and the session still
  // opens with an error message surfaced in the changes snapshot (not a crash).
  @Test
  fun `session opens and changes show error when git binary is unavailable`() {
    val installedRoot = "/home/user/.skill-bill"
    val locator = FakeInstalledWorkspaceLocator(
      result = InstalledWorkspaceAvailability(path = installedRoot, availability = true),
    )
    val gitUnavailableProvisioner = FakeInstalledWorkspaceGitProvisioner(
      result = ProvisionResult.GitUnavailable(
        "git is not available on this system. " +
          "The installed workspace is open, but change history is unavailable.",
      ),
    )
    val viewModel = newViewModel(
      installedWorkspaceLocator = locator,
      installedWorkspaceGitProvisioner = gitUnavailableProvisioner,
    )

    // The VM must not crash. State must be returned.
    val state = viewModel.state()

    // The provisioner must have been called once during init.
    assertEquals(1, gitUnavailableProvisioner.provisionCallCount, "Provisioner must be called once on init")

    // The changes snapshot must carry the error message.
    assertEquals(
      "git is not available on this system. " +
        "The installed workspace is open, but change history is unavailable.",
      state.changes.errorMessage,
      "Changes snapshot must carry the git-unavailable error message",
    )
  }

  // ---- helpers ----

  private fun newViewModel(
    gitGateway: FakeGitGateway = FakeGitGateway(),
    installedWorkspaceLocator: FakeInstalledWorkspaceLocator = FakeInstalledWorkspaceLocator(),
    installedWorkspaceGitProvisioner: FakeInstalledWorkspaceGitProvisioner = FakeInstalledWorkspaceGitProvisioner(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
    repoSessionService: RepoSessionService = InstalledGitTestRepoSessionService(),
    skillTreeService: SkillTreeService = FakeSkillTreeService(emptyList()),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = FakeAuthoringGateway(),
    gitGateway = gitGateway,
    prPublishingGateway = FakePrPublishingGateway(),
    validationGateway = FakeValidationGateway(),
    renderGateway = FakeRenderGateway(),
    recentRepoRepository = recentRepoRepository,
    scaffoldGateway = skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway(),
    firstRunGateway = FakeDesktopFirstRunGateway(
      discoveryResult = skillbill.desktop.core.domain.model.FirstRunDiscoveryResult.Success(
        skillbill.desktop.core.domain.model.FirstRunSetupDiscovery(
          agents = emptyList(),
          platformPacks = emptyList(),
        ),
      ),
      planResult = skillbill.desktop.core.domain.model.FirstRunPlanResult.Failed("not scripted"),
      applyResult = skillbill.desktop.core.domain.model.FirstRunApplyResult.Failed(
        skillbill.desktop.core.domain.model.FirstRunInstallOutcome(
          status = skillbill.desktop.core.domain.model.FirstRunInstallStatus.FAILURE,
          title = "not scripted",
        ),
      ),
    ),
    desktopPreferenceStore = FakeDesktopPreferenceStore(
      initialFirstRunPreferences = skillbill.desktop.core.datastore.DesktopFirstRunPreferences(completed = true),
    ),
    skillRemoveGateway = skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway(),
    installedWorkspaceLocator = installedWorkspaceLocator,
    installedWorkspaceGitProvisioner = installedWorkspaceGitProvisioner,
  )
}

private class InstalledGitTestRepoSessionService : RepoSessionService {
  override fun open(repoPath: String): RepoSession = RepoSession(
    repoPath = repoPath,
    isRecognizedSkillBillRepo = true,
    loadStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
  )
}
