package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import skillbill.desktop.core.designsystem.SkillBillMaterialTheme
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillManagerState
import skillbill.desktop.core.domain.model.MachineSkillTargetDetail
import skillbill.desktop.core.domain.model.MachineToolsState
import skillbill.desktop.core.domain.model.MachineToolsSurface
import kotlin.test.Test
import kotlin.test.assertEquals

class MachineToolsDialogTest {
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `manager keeps adoption action visible while overflowing targets scroll`() = runComposeUiTest {
    var adoptionRequests = 0
    val targets = (1..12).map { index ->
      MachineSkillTargetDetail(
        id = "agent-$index",
        provider = "agent-$index",
        path = "/home/tester/.agent-$index/skills",
        detectionStatus = "DETECTED",
        state = if (index == 1) "PRESENT" else "ABSENT",
        contentIdentity = if (index == 1) "content-demo" else null,
        occurrencePaths = if (index == 1) listOf("/home/tester/.agent-1/skills/demo") else emptyList(),
      )
    }
    val detail = MachineSkillManagerDetail(
      name = "demo",
      description = "A deliberately long skill description that proves the manager remains usable with dense content.",
      ownership = "UNMANAGED",
      provenance = emptyList(),
      canonicalManagedSourcePath = null,
      activeSnapshotHash = null,
      recordIdentity = null,
      contentIdentity = "content-demo",
      targets = targets,
      validationIssues = emptyList(),
    )
    val state = MachineToolsState(
      surface = MachineToolsSurface.MANAGER,
      manager = MachineSkillManagerState(
        rows = listOf(
          MachineSkillManagerRow(
            name = "demo",
            description = detail.description,
            ownership = "UNMANAGED",
            health = "HEALTHY",
            agents = setOf("agent-1"),
          ),
        ),
        selectedName = "demo",
        detail = detail,
      ),
    )
    setContent {
      SkillBillMaterialTheme {
        MachineToolsDialog(
          state = state,
          onAction = {},
          onDismiss = {},
          callbacks = MachineToolsCallbacks(
            managerAction = { if (it == MachineSkillManagerAction.ADOPT) adoptionRequests++ },
          ),
        )
      }
    }

    onNodeWithTag("machine-skill-adopt-action").assertIsDisplayed()
    onNodeWithTag("machine-skill-target-agent-12").performScrollTo().assertIsDisplayed()
    onNodeWithTag("machine-skill-adopt-action").assertIsDisplayed().performClick()

    assertEquals(1, adoptionRequests)
  }
}
