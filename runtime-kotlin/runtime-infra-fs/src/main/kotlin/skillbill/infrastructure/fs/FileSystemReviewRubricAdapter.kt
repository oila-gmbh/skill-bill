package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.model.RuntimeContext
import skillbill.ports.review.ReviewRubricPort
import skillbill.ports.review.model.ReviewSpecialistRubric
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemReviewRubricAdapter(
  private val context: RuntimeContext,
) : ReviewRubricPort {
  override fun loadSpecialistRubrics(stackSlug: String): List<ReviewSpecialistRubric> {
    val skillsRoot = context.userHome.resolve(".skill-bill/installed-skills")
    val prefix = "bill-$stackSlug-code-review-"
    return try {
      Files.newDirectoryStream(skillsRoot).use { stream ->
        stream
          .filter { dir ->
            Files.isDirectory(dir) &&
              dir.fileName.toString().let { name ->
                name.startsWith(prefix) && name.removePrefix(prefix).contains('-')
              }
          }
          .mapNotNull { loadRubric(it) }
          .sortedBy { it.skillName }
      }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
      emptyList()
    }
  }

  private fun loadRubric(dir: Path): ReviewSpecialistRubric? {
    val contentFile = dir.resolve("content.md")
    if (!Files.exists(contentFile)) return null
    val text = Files.readString(contentFile)
    val name = extractNameFromFrontmatter(text) ?: dir.fileName.toString()
    return ReviewSpecialistRubric(skillName = name, content = stripFrontmatter(text))
  }

  private fun extractNameFromFrontmatter(text: String): String? {
    if (!text.startsWith(FRONTMATTER_FENCE)) return null
    val end = text.indexOf(CLOSING_FENCE, FRONTMATTER_FENCE.length)
    return if (end == -1) {
      null
    } else {
      text.substring(FRONTMATTER_FENCE.length, end).lines()
        .firstOrNull { it.trimStart().startsWith("name:") }
        ?.substringAfter("name:")
        ?.trim()
    }
  }

  private fun stripFrontmatter(text: String): String {
    if (!text.startsWith(FRONTMATTER_FENCE)) return text
    val end = text.indexOf(CLOSING_FENCE, FRONTMATTER_FENCE.length)
    return if (end == -1) text else text.substring(end + CLOSING_FENCE.length).trimStart('\n')
  }

  private companion object {
    const val FRONTMATTER_FENCE = "---"
    const val CLOSING_FENCE = "\n---"
  }
}
