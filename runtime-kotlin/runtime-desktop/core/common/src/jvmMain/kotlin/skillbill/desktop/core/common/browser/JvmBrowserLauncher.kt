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
  private val commandAccess: BrowserCommandAccess

  @Inject
  constructor() {
    desktopAccess = AwtBrowserDesktopAccess
    commandAccess = ProcessBrowserCommandAccess
  }

  internal constructor(desktopAccess: BrowserDesktopAccess) {
    this.desktopAccess = desktopAccess
    commandAccess = ProcessBrowserCommandAccess
  }

  internal constructor(desktopAccess: BrowserDesktopAccess, commandAccess: BrowserCommandAccess) {
    this.desktopAccess = desktopAccess
    this.commandAccess = commandAccess
  }

  override fun openCompareUrl(url: String): BrowserLaunchOutcome {
    val awtFailure = tryOpenWithAwt(url)
    if (awtFailure == null) {
      return BrowserLaunchOutcome.Opened
    }
    if (tryOpenWithPlatformCommand(url)) {
      return BrowserLaunchOutcome.Opened
    }
    return BrowserLaunchOutcome.Failed(awtFailure)
  }

  private fun tryOpenWithAwt(url: String): BrowserLaunchFailure? {
    if (!desktopAccess.isDesktopSupported() || !desktopAccess.isBrowseSupported()) {
      return BrowserLaunchFailure.UnsupportedPlatform
    }
    return try {
      desktopAccess.browse(URI.create(url))
      null
    } catch (exception: Exception) {
      BrowserLaunchFailure.LaunchFailed(exception.message)
    }
  }

  private fun tryOpenWithPlatformCommand(url: String): Boolean = platformOpenCommands(url).any { command ->
    try {
      commandAccess.launch(command)
      true
    } catch (_: Exception) {
      false
    }
  }
}

internal interface BrowserDesktopAccess {
  fun isDesktopSupported(): Boolean
  fun isBrowseSupported(): Boolean
  fun browse(uri: URI)
}

internal interface BrowserCommandAccess {
  fun launch(command: List<String>)
}

private object AwtBrowserDesktopAccess : BrowserDesktopAccess {
  override fun isDesktopSupported(): Boolean = Desktop.isDesktopSupported()

  override fun isBrowseSupported(): Boolean = Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)

  override fun browse(uri: URI) {
    Desktop.getDesktop().browse(uri)
  }
}

private object ProcessBrowserCommandAccess : BrowserCommandAccess {
  override fun launch(command: List<String>) {
    ProcessBuilder(command).start()
  }
}

private fun platformOpenCommands(url: String): List<List<String>> {
  val osName = System.getProperty("os.name").lowercase()
  return when {
    "linux" in osName -> listOf(
      listOf("xdg-open", url),
      listOf("gio", "open", url),
      listOf("kde-open5", url),
      listOf("kfmclient", "exec", url),
    )
    "mac" in osName -> listOf(listOf("open", url))
    "windows" in osName -> listOf(listOf("rundll32", "url.dll,FileProtocolHandler", url))
    else -> emptyList()
  }
}
