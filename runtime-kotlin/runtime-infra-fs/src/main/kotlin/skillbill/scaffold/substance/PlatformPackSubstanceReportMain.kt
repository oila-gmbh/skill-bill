package skillbill.scaffold.substance

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path

fun main(arguments: Array<String>) {
  val options = arguments.mapNotNull { argument ->
    argument.removePrefix("--").split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
  }.toMap()
  val root = Path.of(options["repo-root"] ?: ".").toAbsolutePath().normalize()
  val report = PlatformPackSubstanceAudit.audit(root)
  val output = when (options["format"] ?: "text") {
    "json" -> ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report.toWireMap())
    "text" -> report.toHumanReadable()
    else -> error("Unsupported report format '${options["format"]}'; expected text or json.")
  }
  println(output)
  check(report.auditErrors.isEmpty() && report.baselineErrors.isEmpty() && report.blockingViolations.isEmpty()) {
    "Platform pack substance report contains blocking findings."
  }
}

internal fun PlatformPackSubstanceReport.toHumanReadable(): String = buildString {
  appendLine("Platform pack substance contract $contractVersion")
  appendLine("acknowledged violations: ${violations.count { it.acknowledgedBy != null }}")
  appendLine("blocking violations: ${blockingViolations.size}")
  appendLine("audit errors: ${auditErrors.size}")
  appendLine("baseline errors: ${baselineErrors.size}")
  packs.forEach { pack ->
    val maximumPair = pack.highestCorrespondingSimilarity?.similarity?.percentage() ?: "0.00%"
    appendLine(
      "${pack.pack}: physical=${pack.physicalAreas.size} inherited=${pack.inheritedAreas.size} " +
        "shared=${pack.sharedShingles.percentage()} max_pair=$maximumPair quality=${pack.qualityCheckFile}",
    )
    pack.specialists.forEach { specialist ->
      appendLine(
        "  ${specialist.area}${if (specialist.inherited) " (inherited)" else ""}: " +
          "rules=${specialist.substantiveRules} clusters=${specialist.failureModeClusters} " +
          "evidence=${specialist.concreteEvidenceRules} placeholders=${specialist.placeholders.size}",
      )
    }
  }
}

internal fun PlatformPackSubstanceReport.toWireMap(): Map<String, Any?> = linkedMapOf(
  "contract_version" to contractVersion,
  "packs" to packs.map { pack ->
    linkedMapOf(
      "pack" to pack.pack,
      "physical_areas" to pack.physicalAreas,
      "inherited_areas" to pack.inheritedAreas,
      "shared_shingles" to pack.sharedShingles.percentage(),
      "highest_corresponding_similarity" to pack.highestCorrespondingSimilarity?.let { pair ->
        linkedMapOf(
          "role" to pair.role,
          "first_file" to pair.firstFile,
          "second_file" to pair.secondFile,
          "similarity" to pair.similarity.percentage(),
        )
      },
      "quality_check_file" to pack.qualityCheckFile,
      "specialists" to pack.specialists.map { specialist ->
        linkedMapOf(
          "area" to specialist.area,
          "file" to specialist.file,
          "inherited" to specialist.inherited,
          "substantive_rules" to specialist.substantiveRules,
          "failure_mode_clusters" to specialist.failureModeClusters,
          "concrete_evidence_rules" to specialist.concreteEvidenceRules,
          "placeholders" to specialist.placeholders,
        )
      },
    )
  },
  "violations" to violations.map { violation ->
    linkedMapOf(
      "id" to violation.id,
      "pack" to violation.pack,
      "area_or_role" to violation.areaOrRole,
      "files" to violation.files,
      "measured" to violation.measured,
      "target" to violation.target,
      "acknowledged_by" to violation.acknowledgedBy,
    )
  },
  "audit_errors" to auditErrors,
  "baseline_errors" to baselineErrors,
  "blocking_violation_count" to blockingViolations.size,
)
