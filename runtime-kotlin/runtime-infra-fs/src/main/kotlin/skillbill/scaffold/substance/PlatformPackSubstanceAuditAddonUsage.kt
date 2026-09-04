package skillbill.scaffold.substance

import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name

internal fun qualitySections(text: String): List<String> = REQUIRED_QUALITY_SECTIONS.filter { section ->
  text.lineSequence().any { it.trim() == "## $section" }
}

internal fun resolveQualityCheck(slug: String, packs: Map<String, PlatformManifest>): Path? =
  packs[slug]?.declaredQualityCheckFile

internal fun correspondingPairs(files: List<AuthoredFile>): List<SimilarityPair> = files.groupBy {
  roleKey(it)
}.values.flatMap { group ->
  group.sortedBy { it.path.toString() }.let { sorted ->
    sorted.indices.flatMap { left ->
      (left + 1 until sorted.size).map { right ->
        val first = sorted[left]
        val second = sorted[right]
        SimilarityPair(
          roleKey(first),
          relative(first.path),
          relative(second.path),
          jaccard(first.shingles, second.shingles),
        )
      }
    }
  }
}.sortedWith(compareBy({ it.role }, { it.firstFile }, { it.secondFile }))

internal fun roleKey(file: AuthoredFile): String = when (file.role) {
  AuthoredFileRole.SPECIALIST -> "specialist:${file.area}"
  AuthoredFileRole.SIDECAR -> "sidecar:${file.area}:${file.path.name}"
  AuthoredFileRole.BASELINE,
  AuthoredFileRole.QUALITY_CHECK,
  -> file.role.name.lowercase(Locale.ROOT)
}

internal fun relative(path: Path): String {
  val marker = "platform-packs/"
  val value = path.toString().replace('\\', '/')
  return value.substring(value.indexOf(marker).coerceAtLeast(0))
}

internal fun jaccard(first: Set<String>, second: Set<String>): Fraction = fraction(
  first.intersect(second).size,
  first.union(second).size,
)
internal fun fraction(numerator: Int, denominator: Int): Fraction =
  if (denominator == 0) Fraction(0, 1) else Fraction(numerator, denominator)
