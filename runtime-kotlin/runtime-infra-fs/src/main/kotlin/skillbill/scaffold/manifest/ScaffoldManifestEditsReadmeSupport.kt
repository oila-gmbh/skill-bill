@file:Suppress("MagicNumber", "MaxLineLength")

package skillbill.scaffold.manifest

private val README_CATALOG_ROW_PATTERN =
  Regex("""^\| `/(bill-[a-z0-9-]+)` \|[^\n]*$""", RegexOption.MULTILINE)

internal fun findReadmeCatalogRows(text: String): List<MatchResult> =
  README_CATALOG_ROW_PATTERN.findAll(text).toList()
