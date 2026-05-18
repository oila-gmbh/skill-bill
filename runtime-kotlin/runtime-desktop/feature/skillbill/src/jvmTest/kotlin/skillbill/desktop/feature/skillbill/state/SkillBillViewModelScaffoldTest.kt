package skillbill.desktop.feature.skillbill.state

import kotlinx.coroutines.runBlocking
import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldOutcome
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldPlan
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import skillbill.desktop.core.testing.FakeAuthoringGateway
import skillbill.desktop.core.testing.FakeGitGateway
import skillbill.desktop.core.testing.FakePrPublishingGateway
import skillbill.desktop.core.testing.FakeRecentRepoRepository
import skillbill.desktop.core.testing.FakeRenderGateway
import skillbill.desktop.core.testing.FakeRepoSessionService
import skillbill.desktop.core.testing.FakeSkillTreeService
import skillbill.desktop.core.testing.FakeValidationGateway
import skillbill.desktop.core.testing.scaffold.FakeScaffoldGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillBillViewModelScaffoldTest {

  @Test
  fun `openScaffoldWizard hydrates wizard state with catalog snapshot`() = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptedCatalog = ScaffoldCatalogSnapshot(
        approvedCodeReviewAreas = listOf("security"),
        preShellFamilies = listOf("feature-implement"),
        shelledFamilies = listOf("code-review"),
        platformPackPresets = emptyList(),
        pilotedPlatformPacks = emptyList(),
        scaffoldPayloadVersion = "1.0",
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")

    val state = openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    val wizard = assertNotNull(state.scaffoldWizard)
    assertEquals(ScaffoldKind.HORIZONTAL_SKILL, wizard.kind)
    assertEquals(listOf("security"), wizard.optionCatalog.approvedCodeReviewAreas)
    assertEquals(1, gateway.catalogCallCount)
  }

  @Test
  fun `dirty-repo warning surfaces when non-generated changes are pending`() = runBlocking {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(
          ChangedFile(path = "skills/a/content.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M"),
        ),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val state = openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    val wizard = assertNotNull(state.scaffoldWizard)
    assertTrue(wizard.dirtyRepoWarning)
    assertFalse(wizard.runEnabled)
  }

  @Test
  fun `dirty-repo warning suppressed when only generated files differ`() = runBlocking {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(
          ChangedFile(
            path = "skills/a/SKILL.md",
            group = ChangedFileGroup.GENERATED,
            statusCode = "M",
            isGenerated = true,
          ),
        ),
      ),
    )
    val viewModel = newViewModel(gitGateway = gitGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()

    val state = openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    val wizard = assertNotNull(state.scaffoldWizard)
    assertFalse(wizard.dirtyRepoWarning)
  }

  @Test
  fun `dry-run produces preview and execute is gated until plan exists`() = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Preview(
          planned = ScaffoldPlan(
            kind = "horizontal",
            skillName = "bill-foo",
            skillPath = "/repo/skills/bill-foo",
            createdFiles = listOf("/repo/skills/bill-foo/content.md"),
            notes = listOf("Dry run - no filesystem changes applied."),
          ),
        ),
      )
      scriptExecute(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Success(
          result = ScaffoldOutcome(
            kind = "horizontal",
            skillName = "bill-foo",
            skillPath = "/repo/skills/bill-foo",
            createdFiles = listOf("/repo/skills/bill-foo/content.md"),
          ),
        ),
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }

    // Execute is gated until a successful dry-run lands.
    assertNull(viewModel.beginScaffoldExecute())

    val dryRunRequest = assertNotNull(viewModel.beginScaffoldDryRun())
    val dryRunResult = viewModel.runScaffoldDryRun(dryRunRequest)
    val afterPlan = viewModel.finishScaffoldDryRun(dryRunRequest, dryRunResult)
    val planWizard = assertNotNull(afterPlan.scaffoldWizard)
    assertNotNull(planWizard.dryRunPreview)
    assertTrue(planWizard.runEnabled)

    val executeRequest = assertNotNull(viewModel.beginScaffoldExecute())
    val executeResult = viewModel.runScaffoldExecute(executeRequest)
    val afterRun = viewModel.finishScaffoldExecute(executeRequest, executeResult)
    val successWizard = assertNotNull(afterRun.scaffoldWizard)
    assertTrue(successWizard.executionResult is ScaffoldRunResult.Success)
  }

  @Test
  fun `dry-run Failed surfaces banner and clears stale plan`() = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Failed(
          exceptionName = "InvalidScaffoldPayloadError",
          exceptionMessage = "missing name",
          rollbackComplete = true,
        ),
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    val request = assertNotNull(viewModel.beginScaffoldDryRun())
    val result = viewModel.runScaffoldDryRun(request)

    val finalState = viewModel.finishScaffoldDryRun(request, result)
    val wizard = assertNotNull(finalState.scaffoldWizard)
    val failed = wizard.executionResult as ScaffoldRunResult.Failed
    assertEquals("InvalidScaffoldPayloadError", failed.exceptionName)
    assertTrue(failed.rollbackComplete)
    assertNull(wizard.dryRunPreview)
  }

  // F-T04: rollbackComplete=false belongs to the execute path. After a partial-mutation execute
  // failure, both `runEnabled` and the dry-run preview are cleared so the user must re-Plan.
  @Test
  fun `partial mutation result locks Run and Plan until acknowledged`(): Unit = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Preview(
          planned = ScaffoldPlan(kind = "horizontal", skillName = "bill-foo", skillPath = "/repo/skills/bill-foo"),
        ),
      )
      scriptExecute(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Failed(
          exceptionName = "ScaffoldRollbackError",
          exceptionMessage = "rollback failed: dir /tmp/x",
          rollbackComplete = false,
        ),
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    val planRequest = assertNotNull(viewModel.beginScaffoldDryRun())
    viewModel.finishScaffoldDryRun(planRequest, viewModel.runScaffoldDryRun(planRequest))
    val executeRequest = assertNotNull(viewModel.beginScaffoldExecute())
    val executeResult = viewModel.runScaffoldExecute(executeRequest)

    val finalState = viewModel.finishScaffoldExecute(executeRequest, executeResult)
    val wizard = assertNotNull(finalState.scaffoldWizard)
    val failed = wizard.executionResult as ScaffoldRunResult.Failed
    assertFalse(failed.rollbackComplete, "AC5: rollback-failed must surface verbatim")
    // F-102: wizard remains open (NOT auto-dismissed) so the user can read the partial-mutation banner.
    assertNotNull(wizard)
    // F-102: stale plan cleared; runEnabled is false until a fresh Plan lands.
    assertNull(wizard.dryRunPreview)
    assertFalse(wizard.runEnabled, "F-102: Run must be locked after Failed until re-Plan")
    // F-102: plan is also gated for partial-mutation until the user acknowledges the failure banner.
    assertNull(viewModel.beginScaffoldDryRun(), "F-102: Plan locked until acknowledgeScaffoldFailure")
    viewModel.acknowledgeScaffoldFailure()
    assertNotNull(viewModel.beginScaffoldDryRun(), "F-102: Plan re-enabled after acknowledgement")
  }

  // F-T02: payload parity between Plan and Run is asserted at the VM seam (not just the gateway),
  // verifying the view model surfaces the same payload for both modes.
  @Test
  fun `Plan and Run produce identical payloads for every supported kind`() = runBlocking {
    ScaffoldKind.values().forEach { kind ->
      val gateway = FakeScaffoldGateway().apply {
        scriptDryRun(
          kind,
          ScaffoldRunResult.Preview(
            planned = ScaffoldPlan(
              kind = kind.payloadKind,
              skillName = "bill-${kind.payloadKind}",
              skillPath = "/repo/x",
            ),
          ),
        )
        scriptExecute(
          kind,
          ScaffoldRunResult.Success(
            result = ScaffoldOutcome(
              kind = kind.payloadKind,
              skillName = "bill-${kind.payloadKind}",
              skillPath = "/repo/x",
            ),
          ),
        )
      }
      val viewModel = newViewModel(scaffoldGateway = gateway)
      viewModel.selectRepoPath("/repo")
      openWizard(viewModel, kind)
      viewModel.updateScaffoldForm { fillFormFor(kind, it) }
      val planRequest = assertNotNull(viewModel.beginScaffoldDryRun(), "Plan must be available for $kind")
      val planResult = viewModel.runScaffoldDryRun(planRequest)
      viewModel.finishScaffoldDryRun(planRequest, planResult)
      val executeRequest = assertNotNull(viewModel.beginScaffoldExecute(), "Execute must be available for $kind")
      viewModel.runScaffoldExecute(executeRequest)

      val (dryRuns, executes) = gateway.recordedCalls.partition { it.mode == FakeScaffoldGateway.Mode.DRY_RUN }
      assertEquals(1, dryRuns.size, "$kind: expected exactly one dry-run call")
      assertEquals(1, executes.size, "$kind: expected exactly one execute call")
      assertEquals(1, gateway.executeCallCount, "$kind: execute counter must reflect a single call")
      // AC2: payloads differ only by execution mode (which the gateway carries, not the payload).
      assertEquals(
        dryRuns.single().payload.toContractMap(),
        executes.single().payload.toContractMap(),
        "AC2: $kind dry-run and execute payload maps must be identical",
      )
    }
  }

  @Test
  fun `platform-pack scaffold payload uses selected repo root`() = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.PLATFORM_PACK,
        ScaffoldRunResult.Preview(
          planned = ScaffoldPlan(
            kind = "platform-pack",
            skillName = "bill-java-code-review",
            skillPath = "/repo/platform-packs/java",
          ),
        ),
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.PLATFORM_PACK)
    viewModel.updateScaffoldForm { it.copy(platform = "java", displayName = "Java") }

    val request = assertNotNull(viewModel.beginScaffoldDryRun())
    viewModel.runScaffoldDryRun(request)

    assertEquals("/repo", request.payload.toContractMap()["repo_root"])
    assertEquals("/repo", gateway.lastDryRunPayload?.toContractMap()?.get("repo_root"))
  }

  // F-T03: per-kind exhaustive payload map asserted against a golden derived from
  // SCAFFOLD_PAYLOAD.md so the wizard contract surface is enforced (not just spot-checked).
  @Test
  fun `payload toContractMap matches per-kind golden contract shape`() {
    val horizontal = ScaffoldPayload.HorizontalSkill(name = "bill-x", description = "d").toContractMap()
    assertEquals(
      linkedMapOf<String, Any?>(
        "scaffold_payload_version" to "1.0",
        "kind" to "horizontal",
        "name" to "bill-x",
        "description" to "d",
      ),
      horizontal,
    )

    val pack = ScaffoldPayload.PlatformPack(platform = "java", displayName = "Java").toContractMap()
    assertEquals(
      linkedMapOf<String, Any?>(
        "scaffold_payload_version" to "1.0",
        "kind" to "platform-pack",
        "platform" to "java",
        "display_name" to "Java",
        "skeleton_mode" to "full",
      ),
      pack,
    )

    val override = ScaffoldPayload.PlatformOverride(
      platform = "java",
      family = "code-review",
      description = "d",
    ).toContractMap()
    assertEquals(
      linkedMapOf<String, Any?>(
        "scaffold_payload_version" to "1.0",
        "kind" to "platform-override-piloted",
        "platform" to "java",
        "family" to "code-review",
        "description" to "d",
      ),
      override,
    )

    val area = ScaffoldPayload.CodeReviewArea(
      platform = "java",
      area = "security",
      description = "d",
    ).toContractMap()
    assertEquals(
      linkedMapOf<String, Any?>(
        "scaffold_payload_version" to "1.0",
        "kind" to "code-review-area",
        "platform" to "java",
        "area" to "security",
        "description" to "d",
      ),
      area,
    )

    val addon = ScaffoldPayload.AddOn(name = "bill-addon", platform = "java").toContractMap()
    assertEquals(
      linkedMapOf<String, Any?>(
        "scaffold_payload_version" to "1.0",
        "kind" to "add-on",
        "name" to "bill-addon",
        "platform" to "java",
      ),
      addon,
    )
  }

  @Test
  fun `dirty-repo override unlocks Run after plan`() = runBlocking {
    val gitGateway = FakeGitGateway(
      initialSnapshot = ChangesSnapshot(
        files = listOf(
          ChangedFile(path = "skills/a/content.md", group = ChangedFileGroup.UNSTAGED, statusCode = "M"),
        ),
      ),
    )
    val scaffoldGateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Preview(
          planned = ScaffoldPlan(
            kind = "horizontal",
            skillName = "bill-foo",
            skillPath = "/repo/skills/bill-foo",
          ),
        ),
      )
    }
    val viewModel = newViewModel(gitGateway = gitGateway, scaffoldGateway = scaffoldGateway)
    viewModel.selectRepoPath("/repo")
    viewModel.refreshGit()
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    val request = assertNotNull(viewModel.beginScaffoldDryRun())
    val finalState = viewModel.finishScaffoldDryRun(request, viewModel.runScaffoldDryRun(request))

    val beforeOverride = assertNotNull(finalState.scaffoldWizard)
    assertTrue(beforeOverride.dirtyRepoWarning)
    assertFalse(beforeOverride.runEnabled)

    val afterOverride = viewModel.setScaffoldDirtyOverride(true)
    val afterWizard = assertNotNull(afterOverride.scaffoldWizard)
    assertTrue(afterWizard.runEnabled)
  }

  @Test
  fun `busy-gate blocks scaffold open while another operation is running`() = runBlocking {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")
    viewModel.beginValidate()
    assertEquals(SkillBillBusyOperation.VALIDATE, viewModel.state().busyOperation)

    val state = openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    assertNull(state.scaffoldWizard, "scaffold wizard must not open while another op is in-flight")
  }

  @Test
  fun `dismiss clears wizard state`() = runBlocking {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    val dismissed = viewModel.dismissScaffoldWizard()
    assertNull(dismissed.scaffoldWizard)
  }

  // F-401: dismissing the wizard during an in-flight scaffold must release the SCAFFOLD busy slot.
  @Test
  fun `dismiss during in-flight scaffold clears busyOperation slot`() = runBlocking {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    assertNotNull(viewModel.beginScaffoldDryRun())
    assertEquals(SkillBillBusyOperation.SCAFFOLD, viewModel.state().busyOperation)

    val dismissed = viewModel.dismissScaffoldWizard()
    assertNull(dismissed.scaffoldWizard)
    assertNull(dismissed.busyOperation, "F-401: busyOperation must clear on mid-flight dismiss")
  }

  // F-401 mirror: a stale gateway response after dismiss must also release the busy slot if
  // somehow the dismiss raced the response without clearing it.
  @Test
  fun `stale dry-run response after dismiss does not relock busy slot`() = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Preview(planned = ScaffoldPlan(kind = "horizontal", skillName = "x", skillPath = "/x")),
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    val request = assertNotNull(viewModel.beginScaffoldDryRun())
    viewModel.dismissScaffoldWizard()

    // Late response: token mismatch must release busy slot if it was still held.
    val state = viewModel.finishScaffoldDryRun(request, viewModel.runScaffoldDryRun(request))
    assertNull(state.busyOperation)
    assertNull(state.scaffoldWizard)
  }

  // F-408-plat / F-102 / AC5: a Failed result with rollbackComplete=false engages a
  // partial-mutation lock that gates Plan. Switching kinds must NOT silently clear that lock by
  // resetting executionResult — only the explicit acknowledgeScaffoldFailure gesture should
  // release it. Without this guard the user could click a different kind tab to bypass the
  // safety lock and resume scaffolding against a partially mutated repo.
  @Test
  fun `selectScaffoldWizardKind rejects switch while partial-mutation lock is engaged`(): Unit = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Preview(
          planned = ScaffoldPlan(kind = "horizontal", skillName = "bill-foo", skillPath = "/x"),
        ),
      )
      scriptExecute(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Failed(
          exceptionName = "ScaffoldRollbackError",
          exceptionMessage = "rollback failed: dir /tmp/x",
          rollbackComplete = false,
        ),
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    val planRequest = assertNotNull(viewModel.beginScaffoldDryRun())
    viewModel.finishScaffoldDryRun(planRequest, viewModel.runScaffoldDryRun(planRequest))
    val executeRequest = assertNotNull(viewModel.beginScaffoldExecute())
    val executeResult = viewModel.runScaffoldExecute(executeRequest)
    viewModel.finishScaffoldExecute(executeRequest, executeResult)
    // Pre-condition: partial-mutation lock is engaged.
    assertFalse(viewModel.isScaffoldPlanAllowed(), "lock must be engaged after rollbackComplete=false")

    // Attempting a kind switch must be rejected: wizard kind stays HORIZONTAL_SKILL and the lock
    // remains engaged.
    val afterSwitch = viewModel.selectScaffoldWizardKind(ScaffoldKind.PLATFORM_PACK)
    assertEquals(
      ScaffoldKind.HORIZONTAL_SKILL,
      afterSwitch.scaffoldWizard?.kind,
      "F-408-plat: kind switch must be rejected while partial-mutation lock is engaged",
    )
    assertFalse(
      viewModel.isScaffoldPlanAllowed(),
      "F-408-plat: lock must remain engaged after a rejected kind switch",
    )
    assertNull(
      viewModel.beginScaffoldDryRun(),
      "F-408-plat: Plan must still be locked after a rejected kind switch",
    )

    // After explicit acknowledgement the lock releases and kind switching is unblocked.
    viewModel.acknowledgeScaffoldFailure()
    assertTrue(viewModel.isScaffoldPlanAllowed(), "lock releases after acknowledgement")
    val afterUnlock = viewModel.selectScaffoldWizardKind(ScaffoldKind.PLATFORM_PACK)
    assertEquals(
      ScaffoldKind.PLATFORM_PACK,
      afterUnlock.scaffoldWizard?.kind,
      "kind switch must succeed once the lock is acknowledged",
    )
  }

  // F-402: kind switch resets state and discards any in-flight response from the previous kind.
  @Test
  fun `selectScaffoldWizardKind rejects switch while busy and bumps token otherwise`() = runBlocking {
    val viewModel = newViewModel()
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    val request = assertNotNull(viewModel.beginScaffoldDryRun())
    // While busy, kind switch is rejected.
    val blocked = viewModel.selectScaffoldWizardKind(ScaffoldKind.PLATFORM_PACK)
    assertEquals(ScaffoldKind.HORIZONTAL_SKILL, blocked.scaffoldWizard?.kind)
    // Once we settle (simulate a late response), switching kinds clears in-flight state.
    val afterFinish = viewModel.finishScaffoldDryRun(
      request,
      ScaffoldRunResult.Preview(planned = ScaffoldPlan(kind = "horizontal", skillName = "x", skillPath = "/x")),
    )
    assertNotNull(afterFinish.scaffoldWizard?.dryRunPreview)
    val switched = viewModel.selectScaffoldWizardKind(ScaffoldKind.PLATFORM_PACK)
    assertEquals(ScaffoldKind.PLATFORM_PACK, switched.scaffoldWizard?.kind)
    assertNull(switched.scaffoldWizard?.dryRunPreview, "F-402: kind switch must wipe preview")
    assertFalse(switched.scaffoldWizard?.busy ?: true, "F-402: kind switch must reset busy")
  }

  // F-105/F-T01: after Success, the VM can resolve the authored tree-item id for the new artifact
  // and explicitly NOT select a generated wrapper (AC7). Tree-item resolution falls back to null
  // when no authored item is registered (caller leaves selection alone).
  @Test
  fun `resolveAuthoredTreeItemForScaffold returns authored item and skips generated wrappers`() = runBlocking {
    val treeService = FakeSkillTreeService(
      listOf(
        SkillBillTreeItem(
          id = "skills",
          label = "Skills",
          kind = TreeItemKind.GROUP,
          children = listOf(
            SkillBillTreeItem(
              id = "bill-foo-authored",
              label = "bill-foo",
              kind = TreeItemKind.SKILL,
              authoredPath = "skills/bill-foo/content.md",
            ),
            SkillBillTreeItem(
              id = "bill-foo-wrapper",
              label = "SKILL.md",
              kind = TreeItemKind.GENERATED_ARTIFACT,
              authoredPath = "skills/bill-foo/SKILL.md",
            ),
          ),
        ),
      ),
    )
    val viewModel = newViewModel(skillTreeService = treeService)
    viewModel.selectRepoPath("/repo")

    val resolved = viewModel.resolveAuthoredTreeItemForScaffold(
      ScaffoldOutcome(
        kind = "horizontal",
        skillName = "bill-foo",
        skillPath = "/repo/skills/bill-foo",
        createdFiles = listOf("/repo/skills/bill-foo/content.md"),
      ),
    )
    assertEquals("bill-foo-authored", resolved, "AC6/AC7: resolve must pick the authored tree item, not the wrapper")
  }

  @Test
  fun `editing the form after a Plan clears the stale preview`() = runBlocking {
    val gateway = FakeScaffoldGateway().apply {
      scriptDryRun(
        ScaffoldKind.HORIZONTAL_SKILL,
        ScaffoldRunResult.Preview(
          planned = ScaffoldPlan(kind = "horizontal", skillName = "bill-foo", skillPath = "/x"),
        ),
      )
    }
    val viewModel = newViewModel(scaffoldGateway = gateway)
    viewModel.selectRepoPath("/repo")
    openWizard(viewModel, ScaffoldKind.HORIZONTAL_SKILL)
    viewModel.updateScaffoldForm { it.copy(name = "bill-foo") }
    val request = assertNotNull(viewModel.beginScaffoldDryRun())
    val withPreview = viewModel.finishScaffoldDryRun(request, viewModel.runScaffoldDryRun(request))
    assertNotNull(withPreview.scaffoldWizard?.dryRunPreview)

    val edited = viewModel.updateScaffoldForm { it.copy(name = "bill-bar") }
    assertNull(edited.scaffoldWizard?.dryRunPreview)
  }

  // ----- helpers -----

  /**
   * Wrapper that mirrors the route's begin/run/finish discipline for opening a wizard
   * (F-407-arch). Captures the session on the caller dispatcher via `beginOpenScaffoldWizard`,
   * fetches the catalog snapshot via `runOpenScaffoldWizard`, then applies it on the caller
   * dispatcher via `finishOpenScaffoldWizard`.
   */
  private suspend fun openWizard(
    viewModel: SkillBillViewModel,
    kind: ScaffoldKind,
  ): skillbill.desktop.core.domain.model.SkillBillState {
    val request = viewModel.beginOpenScaffoldWizard(kind)
      ?: return viewModel.state()
    val response = viewModel.runOpenScaffoldWizard(request)
    return viewModel.finishOpenScaffoldWizard(response)
  }

  private fun fillFormFor(
    kind: ScaffoldKind,
    fields: skillbill.desktop.core.domain.model.ScaffoldWizardFormFields,
  ): skillbill.desktop.core.domain.model.ScaffoldWizardFormFields = when (kind) {
    ScaffoldKind.HORIZONTAL_SKILL -> fields.copy(name = "bill-foo", description = "d")
    ScaffoldKind.PLATFORM_PACK -> fields.copy(platform = "java", displayName = "Java", description = "d")
    ScaffoldKind.PLATFORM_OVERRIDE_PILOTED -> fields.copy(platform = "java", family = "code-review", description = "d")
    ScaffoldKind.CODE_REVIEW_AREA -> fields.copy(platform = "java", area = "security", description = "d")
    ScaffoldKind.ADD_ON -> fields.copy(name = "bill-addon", platform = "java", description = "d")
  }

  private fun newViewModel(
    repoSessionService: RepoSessionService = FakeRepoSessionService(),
    skillTreeService: SkillTreeService = FakeSkillTreeService(defaultTree()),
    authoringGateway: AuthoringGateway = FakeAuthoringGateway(),
    gitGateway: GitGateway = FakeGitGateway(),
    prPublishingGateway: skillbill.desktop.core.domain.service.PrPublishingGateway = FakePrPublishingGateway(),
    validationGateway: ValidationGateway = FakeValidationGateway(),
    renderGateway: RenderGateway = FakeRenderGateway(),
    recentRepoRepository: FakeRecentRepoRepository = FakeRecentRepoRepository(),
    scaffoldGateway: FakeScaffoldGateway = FakeScaffoldGateway(),
    firstRunGateway: skillbill.desktop.core.domain.service.DesktopFirstRunGateway =
      skillbill.desktop.core.testing.install.FakeDesktopFirstRunGateway(
        discoveryResult = skillbill.desktop.core.domain.model.FirstRunDiscoveryResult.Success(
          skillbill.desktop.core.domain.model.FirstRunSetupDiscovery(agents = emptyList(), platformPacks = emptyList()),
        ),
        planResult = skillbill.desktop.core.domain.model.FirstRunPlanResult.Failed("not scripted"),
        applyResult = skillbill.desktop.core.domain.model.FirstRunApplyResult.Failed(
          skillbill.desktop.core.domain.model.FirstRunInstallOutcome(
            status = skillbill.desktop.core.domain.model.FirstRunInstallStatus.FAILURE,
            title = "not scripted",
          ),
        ),
      ),
    desktopPreferenceStore: skillbill.desktop.core.datastore.DesktopPreferenceStore =
      skillbill.desktop.core.testing.FakeDesktopPreferenceStore(
        initialFirstRunPreferences = skillbill.desktop.core.datastore.DesktopFirstRunPreferences(completed = true),
      ),
  ): SkillBillViewModel = SkillBillViewModel(
    repoSessionService = repoSessionService,
    skillTreeService = skillTreeService,
    authoringGateway = authoringGateway,
    gitGateway = gitGateway,
    prPublishingGateway = prPublishingGateway,
    validationGateway = validationGateway,
    renderGateway = renderGateway,
    recentRepoRepository = recentRepoRepository,
    scaffoldGateway = scaffoldGateway,
    firstRunGateway = firstRunGateway,
    desktopPreferenceStore = desktopPreferenceStore,
    skillRemoveGateway = skillbill.desktop.core.testing.skillremove.FakeSkillRemoveGateway(),
  )

  private fun defaultTree(): List<SkillBillTreeItem> = listOf(
    SkillBillTreeItem(id = "skills", label = "Skills", kind = TreeItemKind.GROUP),
  )

  @Suppress("unused")
  private fun loadedSession(): RepoSession = RepoSession(
    repoPath = "/repo",
    isRecognizedSkillBillRepo = true,
    loadStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
  )
}
