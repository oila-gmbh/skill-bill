package skillbill.install

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val INSTALL_SLUG_MAX_CHARS = 32

/**
 * Predicate replacing the legacy try/catch-on-SkillBillRuntimeException control flow
 * (review F-008): a content-managed skill is one whose source dir contains a regular `content.md`
 * file. Anything else (legacy ad-hoc dirs used by `link-skill`) takes the source-symlink fallback.
 */
internal fun isContentManagedSkill(sourceSkillDir: Path): Boolean {
  val contentMd = sourceSkillDir.resolve("content.md")
  return Files.exists(contentMd, LinkOption.NOFOLLOW_LINKS) &&
    Files.isRegularFile(contentMd, LinkOption.NOFOLLOW_LINKS)
}

internal fun installedSkillSlug(sourceSkillDir: Path): String {
  val raw = sourceSkillDir.fileName?.toString().orEmpty()
  if (raw.isEmpty()) {
    return ""
  }
  val collapsed = raw.lowercase()
    .replace(Regex("[^a-z0-9-]+"), "-")
    .trim('-')
  // F-018: re-trim after `take` so a slug whose 32nd char is `-` doesn't keep a trailing dash
  // (which would later collide with the `<slug>-<hash>` leaf separator).
  return collapsed.take(INSTALL_SLUG_MAX_CHARS).trim('-')
}
