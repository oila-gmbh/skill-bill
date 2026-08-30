
package skillbill.scaffold.platformpack

import skillbill.error.InvalidValidationGateDeclarationError
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator

/**
 * Optional pack-declared validation gate. Absent key → null (agent-run degradation path).
 * Present but malformed → [InvalidValidationGateDeclarationError], never null/"no gate".
 */
internal fun parseValidationGate(manifest: Map<*, *>, slug: String): ValidationGateDeclaration? {
  val raw = manifest["validation_gate"] ?: return null
  val gate = raw as? Map<*, *> ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate' must be a mapping when present.",
  )
  val collectAllFullGateCommand = requireGateArgv(gate, slug, "collect_all_full_gate_command")
  val cacheBypassingCollectAllFullGateCommand = requireGateArgv(
    gate,
    slug,
    "cache_bypassing_collect_all_full_gate_command",
  )
  val buildCommand = optionalGateArgv(gate, slug, "build_command")
  val cacheBypassingBuildCommand = optionalGateArgv(gate, slug, "cache_bypassing_build_command")
  validateBuildCommandsDistinctFromCollectAll(
    slug,
    buildCommand,
    cacheBypassingBuildCommand,
    collectAllFullGateCommand,
    cacheBypassingCollectAllFullGateCommand,
  )
  return ValidationGateDeclaration(
    fullGateCommand = requireGateArgv(gate, slug, "full_gate_command"),
    cacheBypassingFullGateCommand = requireGateArgv(gate, slug, "cache_bypassing_full_gate_command"),
    collectAllFullGateCommand = collectAllFullGateCommand,
    cacheBypassingCollectAllFullGateCommand = cacheBypassingCollectAllFullGateCommand,
    findings = parseValidationGateFindings(gate, slug),
    buildCommand = buildCommand,
    cacheBypassingBuildCommand = cacheBypassingBuildCommand,
    suppressionMarkers = parseSuppressionMarkers(gate, slug),
  )
}

internal fun validateBuildCommandsDistinctFromCollectAll(
  slug: String,
  buildCommand: List<String>?,
  cacheBypassingBuildCommand: List<String>?,
  collectAllFullGateCommand: List<String>,
  cacheBypassingCollectAllFullGateCommand: List<String>,
) {
  if (buildCommand == null) return
  if (buildCommand == collectAllFullGateCommand) {
    invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.build_command' must not be byte-identical to " +
        "'validation_gate.collect_all_full_gate_command'.",
    )
  }
  if (cacheBypassingBuildCommand == cacheBypassingCollectAllFullGateCommand) {
    invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.cache_bypassing_build_command' must not be " +
        "byte-identical to 'validation_gate.cache_bypassing_collect_all_full_gate_command'.",
    )
  }
}

/**
 * Absent or empty `suppression_markers` → empty list (ungated). Present but
 * malformed → loud-fail; never coerce a bad declaration into an ungated empty set.
 */
internal fun parseSuppressionMarkers(gate: Map<*, *>, slug: String): List<String> {
  if (!gate.containsKey("suppression_markers")) return emptyList()
  val raw = gate["suppression_markers"]
  val values = raw as? List<*> ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.suppression_markers' must be an array when present.",
  )
  if (values.isEmpty()) return emptyList()
  return values.mapIndexed { index, value ->
    (value as? String)?.trim()?.takeIf(String::isNotEmpty)
      ?: invalidValidationGateDeclaration(
        "Platform pack '$slug': 'validation_gate.suppression_markers[$index]' must be a non-blank string.",
      )
  }
}

internal fun requireGateArgv(gate: Map<*, *>, slug: String, key: String): List<String> {
  val raw = gate[key] ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.$key' is required when validation_gate is present.",
  )
  return parseGateArgv(raw, slug, key)
}

internal fun optionalGateArgv(gate: Map<*, *>, slug: String, key: String): List<String>? {
  val raw = gate[key] ?: return null
  return parseGateArgv(raw, slug, key)
}

internal fun parseGateArgv(raw: Any?, slug: String, key: String): List<String> {
  val values = raw as? List<*> ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.$key' must be a non-empty argv array.",
  )
  if (values.isEmpty()) {
    invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.$key' must be a non-empty argv array.",
    )
  }
  return values.mapIndexed { index, value ->
    (value as? String)?.trim()?.takeIf(String::isNotEmpty)
      ?: invalidValidationGateDeclaration(
        "Platform pack '$slug': 'validation_gate.$key[$index]' must be a non-blank string.",
      )
  }
}

internal fun parseValidationGateFindings(gate: Map<*, *>, slug: String): ValidationGateFindingsLocator {
  val raw = gate["findings"] ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.findings' is required when validation_gate is present.",
  )
  val findings = raw as? Map<*, *> ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.findings' must be a mapping.",
  )
  val formatRaw = findings["format"] as? String
    ?: invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.format' must be a string.",
    )
  val format = ValidationGateFindingsFormat.fromWire(formatRaw)
    ?: invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.format' '$formatRaw' is not a supported findings format.",
    )
  val globsRaw = findings["artifact_globs"] as? List<*>
    ?: invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.artifact_globs' must be a non-empty array.",
    )
  if (globsRaw.isEmpty()) {
    invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.artifact_globs' must be a non-empty array.",
    )
  }
  val globs = globsRaw.mapIndexed { index, value ->
    (value as? String)?.trim()?.takeIf(String::isNotEmpty)
      ?: invalidValidationGateDeclaration(
        "Platform pack '$slug': 'validation_gate.findings.artifact_globs[$index]' must be a non-blank string.",
      )
  }
  val executedWork = findings["executed_work"]?.let { parseExecutedWorkSignal(it, slug) }
  val compilerDiagnostics = parseCompilerDiagnosticsLocator(findings, slug)
  return ValidationGateFindingsLocator(
    format = format,
    artifactGlobs = globs,
    compilerDiagnostics = compilerDiagnostics,
    executedWork = executedWork,
  )
}

internal fun parseCompilerDiagnosticsLocator(
  findings: Map<*, *>,
  slug: String,
): ValidationGateCompilerDiagnosticsLocator {
  val raw = findings["compiler_diagnostics"] ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.findings.compiler_diagnostics' is required " +
      "when validation_gate is present.",
  )
  val locator = raw as? Map<*, *> ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.findings.compiler_diagnostics' must be a mapping.",
  )
  val formatRaw = locator["format"] as? String
    ?: invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.compiler_diagnostics.format' must be a string.",
    )
  val format = ValidationGateCompilerDiagnosticsFormat.fromWire(formatRaw)
    ?: invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.compiler_diagnostics.format' '$formatRaw' is not supported.",
    )
  return ValidationGateCompilerDiagnosticsLocator(format = format)
}

internal fun parseExecutedWorkSignal(raw: Any?, slug: String): ValidationGateExecutedWorkSignal {
  val mapping = raw as? Map<*, *> ?: invalidValidationGateDeclaration(
    "Platform pack '$slug': 'validation_gate.findings.executed_work' must be a mapping when present.",
  )
  val formatRaw = mapping["format"] as? String
    ?: invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.executed_work.format' must be a string.",
    )
  val format = ValidationGateExecutedWorkFormat.fromWire(formatRaw)
    ?: invalidValidationGateDeclaration(
      "Platform pack '$slug': 'validation_gate.findings.executed_work.format' '$formatRaw' is not supported.",
    )
  return ValidationGateExecutedWorkSignal(format = format)
}
