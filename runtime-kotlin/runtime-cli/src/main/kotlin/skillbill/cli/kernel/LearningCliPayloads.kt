package skillbill.cli.kernel

import skillbill.application.learning.model.LearningDeleteResult
import skillbill.application.learning.model.LearningListResult
import skillbill.application.learning.model.LearningRecordResult
import skillbill.application.learning.model.LearningResolveResult
import skillbill.application.learning.toLearningDeleteContract
import skillbill.application.learning.toLearningListContract
import skillbill.application.learning.toLearningRecordContract
import skillbill.application.learning.toLearningResolveContract

internal fun LearningListResult.toPayload(): Map<String, Any?> = toLearningListContract().toPayload()

internal fun LearningRecordResult.toPayload(): Map<String, Any?> = toLearningRecordContract().toPayload()

internal fun LearningResolveResult.toPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()

internal fun LearningDeleteResult.toPayload(): Map<String, Any?> = toLearningDeleteContract().toPayload()
