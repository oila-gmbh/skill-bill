package skillbill.scaffold.substance

import java.nio.file.Path

internal object PlatformPackSubstanceAuditCore {
  fun audit(repoRoot: Path, policy: SubstancePolicy = SubstancePolicy()): PlatformPackSubstanceReport =
    auditPlatformPacks(repoRoot, policy)

  fun normalize(text: String, names: List<String>): List<String> = normalizeAuthoredText(text, names)

  fun shingles(tokens: List<String>): Set<String> = authoredShingles(tokens)
}
