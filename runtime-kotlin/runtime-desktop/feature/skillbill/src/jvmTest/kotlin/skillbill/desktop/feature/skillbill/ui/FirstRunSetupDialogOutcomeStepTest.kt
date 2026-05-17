package skillbill.desktop.feature.skillbill.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import skillbill.desktop.core.domain.model.FirstRunInstallDetail
import skillbill.desktop.core.domain.model.FirstRunInstallDetailSeverity
import skillbill.desktop.core.domain.model.FirstRunInstallOutcome
import skillbill.desktop.core.domain.model.FirstRunInstallStatus
import skillbill.desktop.core.domain.model.FirstRunSetupState
import skillbill.desktop.core.domain.model.FirstRunSetupStep
import skillbill.desktop.core.domain.model.FirstRunTelemetryLevel
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FirstRunSetupDialogOutcomeStepTest {

  @Test
  fun `result step renders success status and detail row`() = runComposeUiTest {
    setContent {
      FirstRunSetupDialog(
        state = resultState(
          FirstRunInstallOutcome(
            status = FirstRunInstallStatus.SUCCESS,
            title = "Install complete",
            details = listOf(
              FirstRunInstallDetail(
                label = "Codex",
                message = "Installed 12 skills.",
                path = "/home/alex/.codex/skills",
              ),
            ),
          ),
        ),
        callbacks = noOpCallbacks,
      )
    }

    onNodeWithText("Install complete").assertIsDisplayed()
    onNodeWithText("success").assertIsDisplayed()
    onNodeWithText("Codex").assertIsDisplayed()
    onNodeWithText("Installed 12 skills.").assertIsDisplayed()
    onNodeWithText("/home/alex/.codex/skills").assertIsDisplayed()
    onNodeWithText("Done").assertIsDisplayed()
  }

  @Test
  fun `result step renders warning detail with Windows symlink guidance`() = runComposeUiTest {
    setContent {
      FirstRunSetupDialog(
        state = resultState(
          FirstRunInstallOutcome(
            status = FirstRunInstallStatus.WARNING,
            title = "Installed with warnings",
            details = listOf(
              FirstRunInstallDetail(
                label = "Windows symlinks",
                message = "Windows symlink support was not confirmed.",
                severity = FirstRunInstallDetailSeverity.WARNING,
                path = "C:\\Users\\alex\\.skill-bill\\installed-skills",
                guidance = "Enable Developer Mode or rerun from an elevated shell.",
              ),
            ),
          ),
        ),
        callbacks = noOpCallbacks,
      )
    }

    onNodeWithText("Installed with warnings").assertIsDisplayed()
    onNodeWithText("warning").assertIsDisplayed()
    onNodeWithText("Windows symlinks").assertIsDisplayed()
    onNodeWithText("Windows symlink support was not confirmed.").assertIsDisplayed()
    onNodeWithText("C:\\Users\\alex\\.skill-bill\\installed-skills").assertIsDisplayed()
    onNodeWithText("Enable Developer Mode or rerun from an elevated shell.").assertIsDisplayed()
    onNodeWithText("Done").assertIsDisplayed()
  }

  @Test
  fun `result step renders failure status detail and retry action`() = runComposeUiTest {
    setContent {
      FirstRunSetupDialog(
        state = resultState(
          FirstRunInstallOutcome(
            status = FirstRunInstallStatus.FAILURE,
            title = "Install failed",
            details = listOf(
              FirstRunInstallDetail(
                label = "Claude",
                message = "Existing unmanaged file was preserved.",
                severity = FirstRunInstallDetailSeverity.ERROR,
                path = "/home/alex/.claude/skills/bill-feature-implement",
              ),
            ),
          ),
        ),
        callbacks = noOpCallbacks,
      )
    }

    onNodeWithText("Install failed").assertIsDisplayed()
    onNodeWithText("failure").assertIsDisplayed()
    onNodeWithText("Claude").assertIsDisplayed()
    onNodeWithText("Existing unmanaged file was preserved.").assertIsDisplayed()
    onNodeWithText("/home/alex/.claude/skills/bill-feature-implement").assertIsDisplayed()
    onNodeWithText("Retry").assertIsDisplayed()
  }

  @Test
  fun `setup dialog exposes dismiss action`() = runComposeUiTest {
    var dismissed = false
    setContent {
      FirstRunSetupDialog(
        state = FirstRunSetupState(),
        callbacks = noOpCallbacks.copy(onDismiss = { dismissed = true }),
      )
    }

    onNodeWithContentDescription("Dismiss setup wizard").assertIsDisplayed().performClick()

    assertTrue(dismissed)
  }

  private fun resultState(outcome: FirstRunInstallOutcome): FirstRunSetupState = FirstRunSetupState(
    step = FirstRunSetupStep.RESULT,
    selectedAgentIds = setOf("codex"),
    telemetryLevel = FirstRunTelemetryLevel.ANONYMOUS,
    outcome = outcome,
  )

  private companion object {
    val noOpCallbacks = FirstRunSetupCallbacks(
      onAgentSelectionChanged = { _, _ -> },
      onPlatformSelectionChanged = { _, _ -> },
      onTelemetryChanged = {},
      onMcpRegistrationChanged = {},
      onBack = {},
      onNext = {},
      onApply = {},
      onRetry = {},
      onFinish = {},
      onDismiss = {},
    )
  }
}
