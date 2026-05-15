package skillbill.desktop.feature.skillbill.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-X-901 (SKILL-44): unit tests for the dead-affordance cleanup. These follow the
 * SkillBillFrameScaffoldWizardTest convention — pure-handler tests with no Compose runtime — so
 * they keep working without a Compose UI test harness.
 *
 * Scope is intentionally narrow: only the [activateValidationDockAndMaybeRun] helper is exercised
 * here, because that is the only piece of state-shape logic the frame owns directly. Other
 * concerns (route gating on `canStartRepoScopedAction()`, the enum displayed by the kind menu,
 * etc.) are covered by their owning tests — duplicating them here would be tautological.
 */
class SkillBillFrameDeadAffordancesTest {

  @Test
  fun `sidebar Validation handler always activates the Validation dock tab`() {
    var activatedCount = 0
    var validatedCount = 0
    activateValidationDockAndMaybeRun(
      validationIssueCount = 0,
      onActivateValidationTab = { activatedCount += 1 },
      onValidate = { validatedCount += 1 },
    )
    assertEquals(1, activatedCount, "AC3: clicking Validation row must activate the Validation dock tab")
    assertEquals(0, validatedCount, "AC3: with no pending issues the handler must not trigger a fresh validate run")
  }

  @Test
  fun `sidebar Validation handler triggers a fresh validate run when issues are pending`() {
    var activatedCount = 0
    var validatedCount = 0
    activateValidationDockAndMaybeRun(
      validationIssueCount = 3,
      onActivateValidationTab = { activatedCount += 1 },
      onValidate = { validatedCount += 1 },
    )
    assertEquals(1, activatedCount, "AC3: dock-tab activation runs unconditionally on click")
    assertEquals(1, validatedCount, "AC3: pending issues must trigger an additional validate run")
  }
}
