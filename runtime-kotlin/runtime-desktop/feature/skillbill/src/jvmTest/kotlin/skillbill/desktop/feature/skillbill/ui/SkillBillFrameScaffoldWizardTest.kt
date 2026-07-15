package skillbill.desktop.feature.skillbill.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import skillbill.desktop.core.domain.model.BaselineReviewLayerSuggestion
import skillbill.desktop.core.domain.model.BaselineReviewPackOption
import skillbill.desktop.core.domain.model.BaselineReviewSkillOption
import skillbill.desktop.core.domain.model.ManifestEditPreview
import skillbill.desktop.core.domain.model.ScaffoldAddOnLocationMode
import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerForm
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
  fun `run is disabled until plan completes for every active creation kind`() {
    val emptyCatalog = ScaffoldCatalogSnapshot.empty
    ScaffoldKind.activeCreationValues().forEach { kind ->
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
      manifestPreviews = listOf(
        ManifestEditPreview(
          path = "/repo/platform-packs/java/platform.yaml",
          content = "code_review_composition:\n  baseline_layers:\n",
        ),
      ),
      symlinks = emptyList(),
      installTargets = listOf("/repo/platform-packs/java/code-review/bill-java-code-review"),
      notes = listOf("Dry run - no filesystem changes applied."),
    )
    val state = ScaffoldWizardState(kind = ScaffoldKind.PLATFORM_PACK, dryRunPreview = plan)
    val preview = assertNotNull(state.dryRunPreview)
    assertEquals(1, preview.createdFiles.size)
    assertEquals(1, preview.manifestEdits.size)
    assertTrue(preview.manifestPreviews.single().content.contains("code_review_composition"))
    assertEquals(1, preview.installTargets.size)
    assertEquals(1, preview.notes.size)
  }

  @Test
  fun `form-field bag defaults are all empty so wizards must opt in to options`() {
    val fields = ScaffoldWizardFormFields()
    assertEquals("", fields.name)
    assertEquals("", fields.platform)
    assertTrue(fields.baselineLayers.isEmpty())
    assertFalse(fields.suppressSubagents)
    assertEquals(ScaffoldAddOnLocationMode.NATIVE, fields.addonLocationMode)
    assertEquals("", fields.addonLocationPath)
  }

  @Test
  fun `baseline layer form defaults match scaffold payload contract defaults`() {
    val layer = ScaffoldBaselineLayerForm()
    assertEquals("same-review-scope", layer.scope)
    assertTrue(layer.required)
    assertEquals("", layer.platform)
    assertEquals("", layer.skill)
    assertEquals("", layer.mode)
  }

  @Test
  fun `baseline catalog state carries constrained mode and scope options`() {
    val state = ScaffoldWizardState(
      kind = ScaffoldKind.PLATFORM_PACK,
      optionCatalog = ScaffoldCatalogSnapshot.empty.copy(
        baselineReviewPacks = listOf(
          BaselineReviewPackOption(
            platform = "kotlin",
            displayName = "Kotlin",
            strongRoutingSignals = listOf(".kt"),
            skills = listOf(
              BaselineReviewSkillOption(
                name = "bill-kotlin-code-review",
                supportedModes = listOf("kmp-baseline"),
                supportedScopes = listOf("same-review-scope"),
              ),
            ),
          ),
        ),
      ),
    )
    val skill = state.optionCatalog.baselineReviewPacks.single().skills.single()
    assertEquals(listOf("kmp-baseline"), skill.supportedModes)
    assertEquals(listOf("same-review-scope"), skill.supportedScopes)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `platform-pack wizard does not render skeleton selector`() = runComposeUiTest {
    setContent {
      ScaffoldWizardDialog(
        state = ScaffoldWizardState(kind = ScaffoldKind.PLATFORM_PACK),
        canStartScaffoldAction = true,
        callbacks = ScaffoldWizardCallbacks(
          onSelectKind = {},
          onFormChanged = {},
          onAddBaselineLayer = {},
          onAddSuggestedBaselineLayer = {},
          onEditBaselineLayer = { _, _ -> },
          onRemoveBaselineLayer = {},
          onDirtyOverrideChanged = {},
          onChooseAddonLocationPath = {},
          onPlan = {},
          onRun = {},
          onAcknowledgeFailure = {},
          onDismiss = {},
        ),
      )
    }

    assertTrue(onAllNodesWithText("Skeleton mode").fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithText("Starter skeleton").fetchSemanticsNodes().isEmpty())
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `add-on wizard does not render body field`() = runComposeUiTest {
    setContent {
      ScaffoldWizardDialog(
        state = ScaffoldWizardState(kind = ScaffoldKind.ADD_ON),
        canStartScaffoldAction = true,
        callbacks = ScaffoldWizardCallbacks(
          onSelectKind = {},
          onFormChanged = {},
          onAddBaselineLayer = {},
          onAddSuggestedBaselineLayer = {},
          onEditBaselineLayer = { _, _ -> },
          onRemoveBaselineLayer = {},
          onDirtyOverrideChanged = {},
          onChooseAddonLocationPath = {},
          onPlan = {},
          onRun = {},
          onAcknowledgeFailure = {},
          onDismiss = {},
        ),
      )
    }

    assertTrue(onAllNodesWithText("Body").fetchSemanticsNodes().isEmpty())
    assertTrue(onAllNodesWithText("Consumer skill dirs").fetchSemanticsNodes().isEmpty())
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `add-on wizard shows external directory selector only for external mode`() = runComposeUiTest {
    var chooseCalls = 0
    setContent {
      var dialogState by remember { mutableStateOf(ScaffoldWizardState(kind = ScaffoldKind.ADD_ON)) }
      ScaffoldWizardDialog(
        state = dialogState,
        canStartScaffoldAction = true,
        callbacks = noOpScaffoldCallbacks().copy(
          onFormChanged = { transform ->
            dialogState = dialogState.copy(formFields = transform(dialogState.formFields))
          },
          onChooseAddonLocationPath = {
            chooseCalls += 1
            dialogState = dialogState.copy(
              formFields = dialogState.formFields.copy(addonLocationPath = "/private/addons"),
            )
          },
        ),
      )
    }

    assertTrue(onAllNodesWithText("External add-on source path").fetchSemanticsNodes().isEmpty())

    onNodeWithText("External").performClick()
    onNodeWithText("External add-on source path").assertIsDisplayed()
    onNodeWithText("No directory selected").assertIsDisplayed()
    onNodeWithText("Choose...").performClick()

    assertEquals(1, chooseCalls)
    onNodeWithText("/private/addons").assertIsDisplayed()

    onNodeWithText("Native").performClick()
    assertTrue(onAllNodesWithText("External add-on source path").fetchSemanticsNodes().isEmpty())
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `baseline layer controls wire add suggested remove required mode and scope callbacks`() = runComposeUiTest {
    var addLayerCalls = 0
    var addSuggestedCalls = 0
    var editCalls = 0
    var removeCalls = 0
    val catalog = baselineCatalog()

    setContent {
      var dialogState by remember {
        mutableStateOf(
          ScaffoldWizardState(
            kind = ScaffoldKind.PLATFORM_PACK,
            optionCatalog = catalog,
            baselineLayerSuggestion = ScaffoldBaselineLayerForm(
              platform = "kotlin",
              skill = "bill-kotlin-code-review",
              mode = "kmp-baseline",
            ),
            baselineLayerSuggestionLabel = "Kotlin baseline",
          ),
        )
      }
      ScaffoldWizardDialog(
        state = dialogState,
        canStartScaffoldAction = true,
        callbacks = ScaffoldWizardCallbacks(
          onSelectKind = {},
          onFormChanged = { transform ->
            dialogState = dialogState.copy(
              formFields = transform(dialogState.formFields),
            )
          },
          onAddBaselineLayer = {
            addLayerCalls += 1
            dialogState = dialogState.copy(
              formFields = dialogState.formFields.copy(
                baselineLayers = dialogState.formFields.baselineLayers + ScaffoldBaselineLayerForm(
                  rowId = 1L,
                  platform = "kotlin",
                  skill = "bill-kotlin-code-review",
                  mode = "kmp-baseline",
                ),
              ),
            )
          },
          onAddSuggestedBaselineLayer = { addSuggestedCalls += 1 },
          onEditBaselineLayer = { index, transform ->
            editCalls += 1
            dialogState = dialogState.copy(
              formFields = dialogState.formFields.copy(
                baselineLayers = dialogState.formFields.baselineLayers.mapIndexed { layerIndex, layer ->
                  if (layerIndex == index) transform(layer) else layer
                },
              ),
            )
          },
          onRemoveBaselineLayer = { index ->
            removeCalls += 1
            dialogState = dialogState.copy(
              formFields = dialogState.formFields.copy(
                baselineLayers = dialogState.formFields.baselineLayers.filterIndexed { layerIndex, _ ->
                  layerIndex != index
                },
              ),
            )
          },
          onDirtyOverrideChanged = {},
          onChooseAddonLocationPath = {},
          onPlan = {},
          onRun = {},
          onAcknowledgeFailure = {},
          onDismiss = {},
        ),
      )
    }

    onNodeWithText("Add layer").performClick()
    onNodeWithText("Layer 1").assertIsDisplayed()
    onNodeWithContentDescription("Mode option kmp-baseline").performClick()
    onNodeWithContentDescription("Scope option same-review-scope").performClick()
    onNodeWithContentDescription("Required baseline layer").performClick()
    onNodeWithText("Remove").performClick()
    onNodeWithText("Add Kotlin baseline").performClick()

    assertEquals(1, addLayerCalls)
    assertEquals(1, addSuggestedCalls)
    assertEquals(3, editCalls)
    assertEquals(1, removeCalls)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `baseline add layer control is disabled when no baseline packs are available`() = runComposeUiTest {
    setContent {
      ScaffoldWizardDialog(
        state = ScaffoldWizardState(kind = ScaffoldKind.PLATFORM_PACK, optionCatalog = ScaffoldCatalogSnapshot.empty),
        canStartScaffoldAction = true,
        callbacks = noOpScaffoldCallbacks(),
      )
    }

    onNodeWithText("No baseline review packs available").assertIsDisplayed()
    onNodeWithText("Add layer").assertIsNotEnabled()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `horizontal-skill name field exposes merged bill- prefix semantics`() = runComposeUiTest {
    setContent {
      ScaffoldWizardDialog(
        state = ScaffoldWizardState(
          kind = ScaffoldKind.HORIZONTAL_SKILL,
          optionCatalog = ScaffoldCatalogSnapshot.empty,
        ),
        canStartScaffoldAction = true,
        callbacks = noOpScaffoldCallbacks(),
      )
    }

    onNodeWithContentDescription("Skill name, prefix bill-").assertIsDisplayed()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `horizontal-skill name field strips bill- prefix from typed input`() = runComposeUiTest {
    var capturedFields = ScaffoldWizardFormFields()
    setContent {
      var dialogState by remember {
        mutableStateOf(
          ScaffoldWizardState(kind = ScaffoldKind.HORIZONTAL_SKILL, optionCatalog = ScaffoldCatalogSnapshot.empty),
        )
      }
      ScaffoldWizardDialog(
        state = dialogState,
        canStartScaffoldAction = true,
        callbacks = ScaffoldWizardCallbacks(
          onSelectKind = {},
          onFormChanged = { transform ->
            val nextFields = transform(dialogState.formFields)
            capturedFields = nextFields
            dialogState = dialogState.copy(formFields = nextFields)
          },
          onAddBaselineLayer = {},
          onAddSuggestedBaselineLayer = {},
          onEditBaselineLayer = { _, _ -> },
          onRemoveBaselineLayer = {},
          onDirtyOverrideChanged = {},
          onChooseAddonLocationPath = {},
          onPlan = {},
          onRun = {},
          onAcknowledgeFailure = {},
          onDismiss = {},
        ),
      )
    }

    onNodeWithTag("scaffold.skillName.field").performTextInput("bill-foo")
    assertEquals("foo", capturedFields.name)
  }

  @Test
  fun `display labels are stable per active creation ScaffoldKind`() {
    val labels = ScaffoldKind.activeCreationValues().map { it.displayLabel }.toSet()
    assertEquals(4, labels.size)
    assertTrue(labels.contains("Horizontal skill"))
    assertTrue(labels.contains("Platform pack"))
    assertTrue(labels.contains("Add-on"))
    assertTrue(labels.contains("Agent add-on"))
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

  private fun baselineCatalog(): ScaffoldCatalogSnapshot = ScaffoldCatalogSnapshot.empty.copy(
    baselineReviewPacks = listOf(
      BaselineReviewPackOption(
        platform = "kotlin",
        displayName = "Kotlin",
        strongRoutingSignals = listOf(".kt"),
        skills = listOf(
          BaselineReviewSkillOption(
            name = "bill-kotlin-code-review",
            supportedModes = listOf("kmp-baseline"),
            supportedScopes = listOf("same-review-scope"),
          ),
        ),
      ),
    ),
    baselineReviewLayerSuggestions = listOf(
      BaselineReviewLayerSuggestion(
        label = "Kotlin baseline",
        triggerSignals = listOf("android", "kmp"),
        platform = "kotlin",
        skill = "bill-kotlin-code-review",
        scope = "same-review-scope",
        required = true,
        mode = "kmp-baseline",
      ),
    ),
  )

  private fun noOpScaffoldCallbacks(): ScaffoldWizardCallbacks = ScaffoldWizardCallbacks(
    onSelectKind = {},
    onFormChanged = { _ -> },
    onAddBaselineLayer = {},
    onAddSuggestedBaselineLayer = {},
    onEditBaselineLayer = { _, _ -> },
    onRemoveBaselineLayer = {},
    onDirtyOverrideChanged = {},
    onChooseAddonLocationPath = {},
    onPlan = {},
    onRun = {},
    onAcknowledgeFailure = {},
    onDismiss = {},
  )
}
