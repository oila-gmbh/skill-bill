package skillbill.desktop.core.common.browser

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JvmBrowserLauncherTest {
  @Test
  fun `opens compare URL when desktop browse is supported`() {
    val desktopAccess = FakeBrowserDesktopAccess()
    val outcome = JvmBrowserLauncher(desktopAccess).openCompareUrl(COMPARE_URL)

    assertEquals(BrowserLaunchOutcome.Opened, outcome)
    assertEquals(URI.create(COMPARE_URL), desktopAccess.browsedUri)
  }

  @Test
  fun `returns unsupported when desktop browse is unavailable`() {
    val desktopAccess = FakeBrowserDesktopAccess(browseSupported = false)
    val commandAccess = FakeBrowserCommandAccess(launchException = IllegalStateException("missing"))
    val outcome = JvmBrowserLauncher(desktopAccess, commandAccess).openCompareUrl(COMPARE_URL)

    assertEquals(
      BrowserLaunchOutcome.Failed(BrowserLaunchFailure.UnsupportedPlatform),
      outcome,
    )
    assertFalse(desktopAccess.browseCalled)
  }

  @Test
  fun `opens compare URL with platform opener when desktop browse is unavailable`() {
    val desktopAccess = FakeBrowserDesktopAccess(browseSupported = false)
    val commandAccess = FakeBrowserCommandAccess()
    val outcome = JvmBrowserLauncher(desktopAccess, commandAccess).openCompareUrl(COMPARE_URL)

    assertEquals(BrowserLaunchOutcome.Opened, outcome)
    assertEquals(COMPARE_URL, commandAccess.launchedCommands.single().last())
    assertFalse(desktopAccess.browseCalled)
  }

  @Test
  fun `returns launch failure when browse throws`() {
    val desktopAccess = FakeBrowserDesktopAccess(launchException = IllegalStateException("blocked"))
    val commandAccess = FakeBrowserCommandAccess(launchException = IllegalStateException("missing"))
    val outcome = JvmBrowserLauncher(desktopAccess, commandAccess).openCompareUrl(COMPARE_URL)

    assertEquals(
      BrowserLaunchOutcome.Failed(BrowserLaunchFailure.LaunchFailed("blocked")),
      outcome,
    )
  }

  private class FakeBrowserDesktopAccess(
    private val desktopSupported: Boolean = true,
    private val browseSupported: Boolean = true,
    private val launchException: Exception? = null,
  ) : BrowserDesktopAccess {
    var browsedUri: URI? = null
      private set

    val browseCalled: Boolean
      get() = browsedUri != null

    override fun isDesktopSupported(): Boolean = desktopSupported

    override fun isBrowseSupported(): Boolean = browseSupported

    override fun browse(uri: URI) {
      launchException?.let { throw it }
      browsedUri = uri
    }
  }

  private class FakeBrowserCommandAccess(
    private val launchException: Exception? = null,
  ) : BrowserCommandAccess {
    val launchedCommands = mutableListOf<List<String>>()

    override fun launch(command: List<String>) {
      launchException?.let { throw it }
      launchedCommands += command
    }
  }

  private companion object {
    const val COMPARE_URL = "https://github.com/acme/repo/compare/main...feature"
  }
}
