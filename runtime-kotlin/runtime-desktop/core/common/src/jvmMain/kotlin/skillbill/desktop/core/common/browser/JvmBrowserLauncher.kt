package skillbill.desktop.core.common.browser

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.awt.Desktop
import java.net.URI

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JvmBrowserLauncher : BrowserLauncher {
  private val desktopAccess: BrowserDesktopAccess

  @Inject
  constructor() {
    desktopAccess = AwtBrowserDesktopAccess
  }

  internal constructor(desktopAccess: BrowserDesktopAccess) {
    this.desktopAccess = desktopAccess
  }

  override fun openCompareUrl(url: String): BrowserLaunchOutcome {
    if (!desktopAccess.isDesktopSupported() || !desktopAccess.isBrowseSupported()) {
      return BrowserLaunchOutcome.Failed(BrowserLaunchFailure.UnsupportedPlatform)
    }

    return try {
      desktopAccess.browse(URI.create(url))
      BrowserLaunchOutcome.Opened
    } catch (exception: Exception) {
      BrowserLaunchOutcome.Failed(BrowserLaunchFailure.LaunchFailed(exception.message))
    }
  }
}

internal interface BrowserDesktopAccess {
  fun isDesktopSupported(): Boolean
  fun isBrowseSupported(): Boolean
  fun browse(uri: URI)
}

private object AwtBrowserDesktopAccess : BrowserDesktopAccess {
  override fun isDesktopSupported(): Boolean = Desktop.isDesktopSupported()

  override fun isBrowseSupported(): Boolean = Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)

  override fun browse(uri: URI) {
    Desktop.getDesktop().browse(uri)
  }
}
