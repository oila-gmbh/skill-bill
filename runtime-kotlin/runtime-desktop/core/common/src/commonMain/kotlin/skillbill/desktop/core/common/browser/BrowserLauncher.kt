package skillbill.desktop.core.common.browser

interface BrowserLauncher {
  fun openCompareUrl(url: String): BrowserLaunchOutcome
}

sealed interface BrowserLaunchOutcome {
  data object Opened : BrowserLaunchOutcome
  data class Failed(val failure: BrowserLaunchFailure) : BrowserLaunchOutcome
}

sealed interface BrowserLaunchFailure {
  data object UnsupportedPlatform : BrowserLaunchFailure
  data class LaunchFailed(val message: String?) : BrowserLaunchFailure
}
