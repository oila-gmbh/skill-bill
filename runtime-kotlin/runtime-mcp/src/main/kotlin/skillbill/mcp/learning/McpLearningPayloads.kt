package skillbill.mcp.learning

import skillbill.application.learning.model.LearningResolveResult
import skillbill.application.learning.toLearningResolveContract

internal fun LearningResolveResult.toMcpPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()
