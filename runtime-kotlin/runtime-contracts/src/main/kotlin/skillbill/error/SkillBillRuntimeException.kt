package skillbill.error

/** Base exception for runtime-kotlin failures during the staged migration. */
open class SkillBillRuntimeException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
