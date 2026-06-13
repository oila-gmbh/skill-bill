package skillbill.mcp.learning

import skillbill.application.learning.toLearningResolveContract
import skillbill.application.model.LearningResolveResult

internal fun LearningResolveResult.toMcpPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()
