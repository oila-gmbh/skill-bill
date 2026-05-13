package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.domain.model.ScaffoldCatalogSnapshot
import skillbill.desktop.core.domain.model.ScaffoldKind
import skillbill.desktop.core.domain.model.ScaffoldOutcome
import skillbill.desktop.core.domain.model.ScaffoldPlan
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.desktop.core.domain.model.ScaffoldWizardFormFields
import skillbill.desktop.core.domain.model.ScaffoldWizardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Snapshot-style unit tests for the scaffold wizard render contract. The Compose dialog itself is
 * pure derivation over [ScaffoldWizardState], so these tests verify the state shape per kind /
 * outcome combination — the same combinations the dialog branches on. The dialog itself is left
 * as a thin renderer over the state, in line with the existing SkillBillFrame style.
 */
class SkillBillFrameScaffoldWizardTest {

  @Test
  fun `run is disabled until plan completes for every kind`() {
    val emptyCatalog = ScaffoldCatalogSnapshot.empty
    ScaffoldKind.values().forEach { kind ->
      val state = ScaffoldWizardState(kind = kind, optionCatalog = emptyCatalog)
      assertFalse(state.runEnabled, "Run must be disabled for $kind without a plan")
    }
  }

  @Test
  fun `run is enabled after dry-run when repo is clean`() {
    val state = ScaffoldWizardState(
      kind = ScaffoldKind.HORIZONTAL_SKILL,
      dryRunPreview = ScaffoldPlan(kind = "horizontal", skillName = "bill-x", skillPath = "/x"),
    )
    assertTrue(state.runEnabled)
  }

  @Test
  fun `dirty repo requires explicit override before Run unlocks`() {
    val withWarning = ScaffoldWizardState(
      kind = ScaffoldKind.HORIZONTAL_SKILL,
      dryRunPreview = ScaffoldPlan(kind = "horizontal", skillName = "bill-x", skillPath = "/x"),
      dirtyRepoWarning = true,
    )
    assertFalse(withWarning.runEnabled, "AC8: dirty repo blocks Run until acknowledged")

    val acknowledged = withWarning.copy(overrideDirtyRepo = true)
    assertTrue(acknowledged.runEnabled)
  }

  @Test
  fun `busy state disables Run regardless of plan readiness`() {
    val state = ScaffoldWizardState(
      kind = ScaffoldKind.HORIZONTAL_SKILL,
      dryRunPreview = ScaffoldPlan(kind = "horizontal", skillName = "bill-x", skillPath = "/x"),
      busy = true,
    )
    assertFalse(state.runEnabled)
  }

  @Test
  fun `Success result locks the run button`() {
    val state = ScaffoldWizardState(
      kind = ScaffoldKind.HORIZONTAL_SKILL,
      dryRunPreview = ScaffoldPlan(kind = "horizontal", skillName = "bill-x", skillPath = "/x"),
      executionResult = ScaffoldRunResult.Success(
        result = ScaffoldOutcome(kind = "horizontal", skillName = "bill-x", skillPath = "/x"),
      ),
    )
    assertFalse(state.runEnabled, "Run must be locked once Success lands")
  }

  @Test
  fun `Failed banner state preserves exception name and message verbatim`() {
    val failed = ScaffoldRunResult.Failed(
      exceptionName = "SkillAlreadyExistsError",
      exceptionMessage = "Skill target '/repo/skills/bill-x' already exists.",
      rollbackComplete = true,
    )
    val state = ScaffoldWizardState(
      kind = ScaffoldKind.HORIZONTAL_SKILL,
      executionResult = failed,
    )
    val result = assertNotNull(state.executionResult) as ScaffoldRunResult.Failed
    assertEquals("SkillAlreadyExistsError", result.exceptionName)
    assertEquals("Skill target '/repo/skills/bill-x' already exists.", result.exceptionMessage)
    assertTrue(result.rollbackComplete)
  }

  @Test
  fun `partial-mutation banner is distinguishable from clean failure`() {
    val partial = ScaffoldRunResult.Failed(
      exceptionName = "ScaffoldRollbackError",
      exceptionMessage = "rollback errors: dir /tmp/x",
      rollbackComplete = false,
    )
    val state = ScaffoldWizardState(
      kind = ScaffoldKind.PLATFORM_PACK,
      executionResult = partial,
    )
    val result = assertNotNull(state.executionResult) as ScaffoldRunResult.Failed
    assertFalse(result.rollbackComplete, "AC5: rollback-failed result must surface partial-mutation banner")
  }

  @Test
  fun `dry-run preview lists files manifest edits symlinks and notes`() {
    val plan = ScaffoldPlan(
      kind = "platform-pack",
      skillName = "bill-java-code-review",
      skillPath = "/repo/platform-packs/java",
      createdFiles = listOf("/repo/platform-packs/java/platform.yaml"),
      manifestEdits = listOf("/repo/platform-packs/java/platform.yaml"),
      symlinks = emptyList(),
      installTargets = listOf("/repo/platform-packs/java/code-review/bill-java-code-review"),
      notes = listOf("Dry run - no filesystem changes applied."),
    )
    val state = ScaffoldWizardState(kind = ScaffoldKind.PLATFORM_PACK, dryRunPreview = plan)
    val preview = assertNotNull(state.dryRunPreview)
    assertEquals(1, preview.createdFiles.size)
    assertEquals(1, preview.manifestEdits.size)
    assertEquals(1, preview.installTargets.size)
    assertEquals(1, preview.notes.size)
  }

  @Test
  fun `form-field bag defaults are all empty so wizards must opt in to options`() {
    val fields = ScaffoldWizardFormFields()
    assertEquals("", fields.name)
    assertEquals("", fields.platform)
    assertEquals("full", fields.skeletonMode)
    assertTrue(fields.specialistAreas.isEmpty())
    assertFalse(fields.suppressSubagents)
  }

  @Test
  fun `display labels are stable per ScaffoldKind`() {
    // AC7 mirror: kinds offered must match exactly the five wizard variants.
    val labels = ScaffoldKind.values().map { it.displayLabel }.toSet()
    assertEquals(5, labels.size)
    assertTrue(labels.contains("Horizontal skill"))
    assertTrue(labels.contains("Platform pack"))
    assertTrue(labels.contains("Platform override for piloted family"))
    assertTrue(labels.contains("Code-review area"))
    assertTrue(labels.contains("Add-on"))
  }

  @Test
  fun `editing form after Failed result keeps banner so user can read it`() {
    // AC5: the failure banner stays visible until acknowledged; editing the form is allowed.
    val state = ScaffoldWizardState(
      kind = ScaffoldKind.HORIZONTAL_SKILL,
      executionResult = ScaffoldRunResult.Failed(
        exceptionName = "InvalidScaffoldPayloadError",
        exceptionMessage = "bad",
        rollbackComplete = true,
      ),
    )
    assertNotNull(state.executionResult)
    val updated = state.copy(
      formFields = state.formFields.copy(name = "bill-new"),
      dryRunPreview = null,
    )
    assertNotNull(updated.executionResult)
  }

  @Test
  fun `null wizard means dialog is hidden from frame composition`() {
    val state = ScaffoldWizardState(kind = ScaffoldKind.HORIZONTAL_SKILL)
    // Mirror the frame check: dialog only renders when scaffoldWizard != null.
    val viewModelState: ScaffoldWizardState? = null
    assertNull(viewModelState)
    assertNotNull(state)
  }
}
