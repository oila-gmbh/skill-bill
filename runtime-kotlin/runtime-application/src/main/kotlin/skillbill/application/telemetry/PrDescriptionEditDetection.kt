package skillbill.application.telemetry

fun prDescriptionWasEditedByUser(generatedDescription: String?, finalPrBody: String?): Boolean {
  val generated = generatedDescription?.normalizedPrDescriptionBody() ?: return false
  val actual = finalPrBody?.normalizedPrDescriptionBody() ?: return false
  return generated != actual
}

private fun String.normalizedPrDescriptionBody(): String = trim()
  .replace("\r\n", "\n")
  .replace('\r', '\n')
  .lines()
  .joinToString("\n") { it.trimEnd() }
