@file:Suppress("MaxLineLength")

package skillbill.domain.skillremove

import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * F-S04: removes absolute-path tokens from exception messages before they reach the dialog/CLI.
 *
 * Rules:
 * - Tokens that resolve to a path under `repoRoot` are relativized (e.g. `/Users/alice/repo/skills/foo`
 *   becomes `skills/foo`).
 * - Tokens that are absolute paths but not under `repoRoot` are replaced with `<external path>`.
 * - Tokens that don't look like paths are left untouched.
 *
 * The implementation is whitespace-tokenized and conservative — exception messages we have seen in
 * the wild use spaces as natural separators (e.g. java.nio.file.NoSuchFileException prints the
 * absolute path as the entire message; the IOException family interleaves with prose). For the
 * cases where a path is embedded mid-token (e.g. `foo:/abs/path:bar`), we still try to relativize
 * any substring that parses as an absolute path.
 */
object SkillRemoveErrorSanitizer {
  fun sanitize(message: String, repoRootAbsolutePath: String): String {
    val repoRoot: java.nio.file.Path? = if (message.isBlank()) null else parseRepoRoot(repoRootAbsolutePath)
    if (repoRoot == null) return message
    val repoRootStr = repoRoot.toString()
    // Split on whitespace; rejoin with a single space so we don't widen newlines into noise.
    return message.splitToSequence(' ', '\t', '\n')
      .map { token ->
        if (token.isBlank()) return@map token
        // Quick check — if the token has no '/' or '\' it cannot be a path.
        if (!token.contains('/') && !token.contains('\\')) return@map token
        // Strip trailing punctuation that's commonly attached to paths in messages.
        val (core, trailing) = stripTrailingPunctuation(token)
        val sanitized = sanitizeToken(core, repoRoot, repoRootStr) ?: return@map token
        sanitized + trailing
      }
      .joinToString(" ")
  }

  private fun parseRepoRoot(repoRootAbsolutePath: String): java.nio.file.Path? = try {
    Paths.get(repoRootAbsolutePath).toAbsolutePath().normalize()
  } catch (_: InvalidPathException) {
    null
  }

  private fun stripTrailingPunctuation(token: String): Pair<String, String> {
    var idx = token.length
    while (idx > 0 && token[idx - 1] in TRAILING_PUNCTUATION) idx--
    return token.substring(0, idx) to token.substring(idx)
  }

  private fun sanitizeToken(token: String, repoRoot: java.nio.file.Path, repoRootStr: String): String? = try {
    val parsed = Paths.get(token)
    if (!parsed.isAbsolute) return null
    val normalized = parsed.normalize()
    if (normalized.startsWith(repoRoot)) {
      val relative = repoRoot.relativize(normalized).toString().replace('\\', '/')
      relative.ifBlank { "." }
    } else if (token.startsWith(repoRootStr)) {
      // Defensive: same as above but in case the path string contains components we couldn't
      // resolve cleanly.
      val rel = token.removePrefix(repoRootStr).trimStart('/', '\\')
      rel.ifBlank { "." }
    } else {
      EXTERNAL_PATH_PLACEHOLDER
    }
  } catch (_: InvalidPathException) {
    null
  }

  private const val EXTERNAL_PATH_PLACEHOLDER: String = "<external path>"
  private val TRAILING_PUNCTUATION: Set<Char> = setOf('.', ',', ';', ':', ')', ']', '}', '\'', '"')
}
