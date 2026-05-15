package skillbill.desktop.feature.skillbill.ui

import skillbill.desktop.core.common.browser.BrowserLaunchFailure
import skillbill.desktop.core.common.browser.BrowserLaunchOutcome
import skillbill.desktop.core.common.browser.BrowserLauncher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillBillRouteCompareUrlTest {
  @Test
  fun `successful compare URL launch does not copy fallback URL`() {
    val copiedUrls = mutableListOf<String>()
    val outcome = handleCompareUrlActivation(
      url = COMPARE_URL,
      browserLauncher = FakeBrowserLauncher(BrowserLaunchOutcome.Opened),
      copyUrl = copiedUrls::add,
    )

    assertEquals(BrowserLaunchOutcome.Opened, outcome)
    assertTrue(copiedUrls.isEmpty())
  }

  @Test
  fun `unsupported compare URL launch copies fallback URL`() {
    val copiedUrls = mutableListOf<String>()
    val outcome = handleCompareUrlActivation(
      url = COMPARE_URL,
      browserLauncher = FakeBrowserLauncher(
        BrowserLaunchOutcome.Failed(BrowserLaunchFailure.UnsupportedPlatform),
      ),
      copyUrl = copiedUrls::add,
    )

    assertEquals(
      BrowserLaunchOutcome.Failed(BrowserLaunchFailure.UnsupportedPlatform),
      outcome,
    )
    assertEquals(listOf(COMPARE_URL), copiedUrls)
  }

  @Test
  fun `throwing compare URL launcher copies fallback URL`() {
    val copiedUrls = mutableListOf<String>()
    val outcome = handleCompareUrlActivation(
      url = COMPARE_URL,
      browserLauncher = FakeBrowserLauncher(throwOnOpen = IllegalStateException("boom")),
      copyUrl = copiedUrls::add,
    )

    assertEquals(
      BrowserLaunchOutcome.Failed(BrowserLaunchFailure.LaunchFailed("boom")),
      outcome,
    )
    assertEquals(listOf(COMPARE_URL), copiedUrls)
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
