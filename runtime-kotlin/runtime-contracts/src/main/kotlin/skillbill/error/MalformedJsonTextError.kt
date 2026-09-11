package skillbill.error

class MalformedJsonTextError(cause: Throwable) : ShellContentContractException(
  "JSON text is malformed: ${cause.message.orEmpty()}",
  cause,
)
