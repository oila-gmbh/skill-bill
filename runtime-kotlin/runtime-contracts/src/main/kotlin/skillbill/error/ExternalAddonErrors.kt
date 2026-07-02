package skillbill.error

class ExternalAddonConfigError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class ExternalAddonOverlayError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)
