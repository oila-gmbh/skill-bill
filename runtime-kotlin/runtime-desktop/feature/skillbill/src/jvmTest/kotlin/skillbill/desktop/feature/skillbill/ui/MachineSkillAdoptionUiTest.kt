package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import skillbill.desktop.core.designsystem.SkillBillMaterialTheme
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineSkillAdoptionUiTest {
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `read only unmanaged skill exposes adoption action`() = runComposeUiTest {
    var adoptionRequests = 0
    val editor = EditorPlaceholder(
      title = "demo",
      detail = "demo",
      skillName = "demo",
      kind = "Third-party runtime skill",
      content = "Demo skill",
      readOnlyReason = "Adopt this unmanaged skill to edit it safely.",
      machineSkillDetail = MachineSkillManagerDetail(
        name = "demo",
        description = "Demo skill",
        ownership = "UNMANAGED",
        provenance = emptyList(),
        canonicalManagedSourcePath = null,
        activeSnapshotHash = null,
        recordIdentity = null,
        contentIdentity = "content-demo",
        targets = emptyList(),
        validationIssues = emptyList(),
      ),
    )
    setContent {
      SkillBillMaterialTheme {
        CodeEditor(
          editor = editor,
          dirtyEditorPrompt = null,
          editorInputEnabled = true,
          onDraftChanged = {},
          onSave = {},
          onRevert = {},
          onAdoptMachineSkill = { adoptionRequests++ },
          onDirtyPromptDiscard = {},
          onDirtyPromptCancel = {},
        )
      }
    }

    onNodeWithText("Adopt").assertHasClickAction().performClick()

    assertEquals(1, adoptionRequests)
  }
}
