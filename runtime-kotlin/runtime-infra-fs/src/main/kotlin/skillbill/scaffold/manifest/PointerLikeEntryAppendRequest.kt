package skillbill.scaffold.manifest

internal data class PointerLikeEntryAppendRequest(
  val text: String,
  val blockName: String,
  val skillRelativeDir: String,
  val entryName: String,
  val renderBlock: () -> String,
  val renderEntry: () -> String,
  val existingEntryPattern: Regex,
)
