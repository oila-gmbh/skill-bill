package skillbill.model

/**
 * A filesystem location the domain can carry, compare and compose without importing `java.nio`.
 *
 * The wrapped string is whatever the adapter that produced it printed, so interpolating a
 * [FileLocation] yields the same text a `java.nio.file.Path` did. Adapters convert at the field
 * boundary through the `toPath` / `toFileLocation` extensions in `runtime-ports`.
 *
 * Every member is pure segment arithmetic over `/`-separated values; nothing here touches a
 * filesystem or a working directory, so a relative value stays relative.
 */
@JvmInline
value class FileLocation(val value: String) : Comparable<FileLocation> {
  val fileName: String get() = value.trimEnd(SEPARATOR).substringAfterLast(SEPARATOR)

  val isAbsolute: Boolean get() = value.startsWith(SEPARATOR)

  val segments: List<String> get() = value.split(SEPARATOR).filter(String::isNotEmpty)

  fun resolve(segment: String): FileLocation = when {
    segment.startsWith(SEPARATOR) -> FileLocation(segment)
    value.isEmpty() -> FileLocation(segment)
    segment.isEmpty() -> this
    else -> FileLocation(value.trimEnd(SEPARATOR) + SEPARATOR + segment)
  }

  fun normalized(): FileLocation {
    val resolved = mutableListOf<String>()
    segments.forEach { segment ->
      when {
        segment == CURRENT -> Unit
        segment != PARENT -> resolved += segment
        resolved.lastOrNull()?.takeIf { last -> last != PARENT } != null -> resolved.removeAt(resolved.lastIndex)
        !isAbsolute -> resolved += segment
        else -> Unit
      }
    }
    return FileLocation(join(resolved))
  }

  fun startsWith(prefix: FileLocation): Boolean {
    val prefixSegments = prefix.segments
    return isAbsolute == prefix.isAbsolute &&
      segments.size >= prefixSegments.size &&
      segments.subList(0, prefixSegments.size) == prefixSegments
  }

  fun relativize(other: FileLocation): FileLocation =
    FileLocation(other.segments.drop(segments.size).joinToString(SEPARATOR.toString()))

  override fun compareTo(other: FileLocation): Int = value.compareTo(other.value)

  override fun toString(): String = value

  private fun join(parts: List<String>): String =
    if (isAbsolute) SEPARATOR + parts.joinToString(SEPARATOR.toString()) else parts.joinToString(SEPARATOR.toString())

  private companion object {
    const val SEPARATOR: Char = '/'
    const val CURRENT: String = "."
    const val PARENT: String = ".."
  }
}
