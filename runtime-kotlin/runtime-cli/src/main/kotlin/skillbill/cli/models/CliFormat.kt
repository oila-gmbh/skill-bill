package skillbill.cli.models

enum class CliFormat(val wireName: String) {
  TEXT("text"),
  JSON("json"),
  ;

  companion object {
    fun fromWireName(rawValue: String): CliFormat = when (rawValue) {
      TEXT.wireName -> TEXT
      JSON.wireName -> JSON
      else -> throw IllegalArgumentException("--format must be one of: text, json.")
    }
  }
}
