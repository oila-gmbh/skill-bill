package skillbill.scaffold.substance

import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.CODE_REVIEW_FALLBACK_CAPABILITY
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.policy.scaffold.APPROVED_CODE_REVIEW_AREAS
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo


object PlatformPackSubstanceAudit {
  fun audit(repoRoot: Path, policy: SubstancePolicy = SubstancePolicy()): PlatformPackSubstanceReport =
    PlatformPackSubstanceAuditCore.audit(repoRoot, policy)

  fun normalize(text: String, names: Collection<String> = emptyList()): List<String> =
    PlatformPackSubstanceAuditCore.normalize(text, names)

  fun shingles(tokens: List<String>): Set<String> = PlatformPackSubstanceAuditCore.shingles(tokens)
}
