package skillbill.mcp.core

import skillbill.mcp.review.GovernedReviewEvidenceBridge

fun main() {
  val environment = System.getenv()
  if (GovernedReviewEvidenceBridge.enabled(environment)) {
    GovernedReviewEvidenceBridge.run(environment)
  } else {
    McpStdioServer.run()
  }
}
