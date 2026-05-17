package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.common.browser.BrowserLaunchFailure
import skillbill.desktop.core.common.browser.BrowserLaunchOutcome
import skillbill.desktop.core.common.browser.BrowserLauncher
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillBillRouteCompareUrlTest {
  @Test
  fun `successful compare URL launch returns opened`() {
    val outcome = handleCompareUrlActivation(
      url = COMPARE_URL,
      browserLauncher = FakeBrowserLauncher(BrowserLaunchOutcome.Opened),
    )

    assertEquals(BrowserLaunchOutcome.Opened, outcome)
  }

  @Test
  fun `unsupported compare URL launch returns failure without clipboard fallback`() {
    val outcome = handleCompareUrlActivation(
      url = COMPARE_URL,
      browserLauncher = FakeBrowserLauncher(
        BrowserLaunchOutcome.Failed(BrowserLaunchFailure.UnsupportedPlatform),
      ),
    )

    assertEquals(
      BrowserLaunchOutcome.Failed(BrowserLaunchFailure.UnsupportedPlatform),
      outcome,
    )
  }

  @Test
  fun `throwing compare URL launcher returns failure without clipboard fallback`() {
    val outcome = handleCompareUrlActivation(
      url = COMPARE_URL,
      browserLauncher = FakeBrowserLauncher(throwOnOpen = IllegalStateException("boom")),
    )

    assertEquals(
      BrowserLaunchOutcome.Failed(BrowserLaunchFailure.LaunchFailed("boom")),
      outcome,
    )
  }

  private class FakeBrowserLauncher(
    private val outcome: BrowserLaunchOutcome? = null,
    private val throwOnOpen: Exception? = null,
  ) : BrowserLauncher {
    override fun openCompareUrl(url: String): BrowserLaunchOutcome {
      throwOnOpen?.let { throw it }
      return outcome ?: error("Fake outcome is required when not throwing.")
    }
  }

  private companion object {
    const val COMPARE_URL = "https://github.com/acme/repo/compare/main...feature"
  }
}
