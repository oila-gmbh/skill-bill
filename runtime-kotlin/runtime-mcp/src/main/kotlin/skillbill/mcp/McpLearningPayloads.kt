package skillbill.mcp

import skillbill.application.LearningResolveResult
import skillbill.application.toLearningResolveContract

internal fun LearningResolveResult.toMcpPayload(): Map<String, Any?> = toLearningResolveContract().toPayload()
