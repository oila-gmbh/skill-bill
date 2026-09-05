package skillbill.error

class InvalidPortableReviewBaselineSchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)
