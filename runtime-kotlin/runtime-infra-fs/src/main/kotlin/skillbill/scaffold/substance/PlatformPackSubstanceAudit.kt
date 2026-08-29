package skillbill.scaffold.substance

import java.nio.file.Path

object PlatformPackSubstanceAudit {
  fun audit(repoRoot: Path, policy: SubstancePolicy = SubstancePolicy()): PlatformPackSubstanceReport =
    PlatformPackSubstanceAuditCore.audit(repoRoot, policy)

  fun normalize(text: String, names: Collection<String> = emptyList()): List<String> =
    PlatformPackSubstanceAuditCore.normalize(text, names.toList())

  fun shingles(tokens: List<String>): Set<String> = PlatformPackSubstanceAuditCore.shingles(tokens)
}
