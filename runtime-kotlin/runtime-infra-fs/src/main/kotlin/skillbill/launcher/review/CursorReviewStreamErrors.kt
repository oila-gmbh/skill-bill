package skillbill.launcher.review

open class CursorReviewStreamError(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

class CursorReviewStreamMalformedError(
  message: String,
  cause: Throwable? = null,
) : CursorReviewStreamError(message, cause)

class CursorReviewStreamEmptyError(message: String) : CursorReviewStreamError(message)

class CursorReviewStreamForbiddenOperationError(message: String) : CursorReviewStreamError(message)

class CursorReviewStreamProviderFailureError(
  message: String,
  cause: Throwable? = null,
) : CursorReviewStreamError(message, cause)

class CursorReviewStreamTerminationError(message: String) : CursorReviewStreamError(message)
