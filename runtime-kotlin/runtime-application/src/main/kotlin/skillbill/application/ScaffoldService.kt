package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.scaffold.model.ScaffoldResult
import java.nio.file.Path

/**
 * SKILL-52.1 subtask 2 note: the new capability-named scaffold ports
 * (`Scaffold{SourceLoader,ManifestPersistence,GeneratedStaging,InstallLink,RepoValidation}Port`)
 * are wired in `RuntimeComponent` so subtask 3 can migrate this service over without further
 * DI churn. This file intentionally still depends on the legacy [ScaffoldGateway] because
 * eliminating its `Map<String, Any?>` surfaces and the matching CLI/MCP envelope mapping is
 * scoped to subtask 3 — migrating here without that elimination would change externally
 * observable raw-map shapes the goldens guard.
 */
@Inject
class ScaffoldService(
  private val gateway: ScaffoldGateway,
) {
  fun list(repoRoot: Path, skillNames: List<String>): Map<String, Any?> = gateway.list(repoRoot, skillNames)

  fun show(repoRoot: Path, skillName: String, contentMode: String): Map<String, Any?> =
    gateway.show(repoRoot, skillName, contentMode)

  fun explain(repoRoot: Path, skillName: String?): Map<String, Any?> = gateway.explain(repoRoot, skillName)

  fun validate(repoRoot: Path, skillNames: List<String>): Map<String, Any?> = gateway.validate(repoRoot, skillNames)

  fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean): Map<String, Any?> =
    gateway.upgrade(repoRoot, skillNames, validate)

  fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?): Map<String, Any?> =
    gateway.fill(repoRoot, skillName, body, sectionName)

  fun saveExactContent(repoRoot: Path, skillName: String, content: String): Map<String, Any?> =
    gateway.saveExactContent(repoRoot, skillName, content)

  fun editWithBodyFile(repoRoot: Path, skillName: String, body: String, sectionName: String?): Map<String, Any?> =
    gateway.editWithBodyFile(repoRoot, skillName, body, sectionName)

  fun scaffold(payload: Map<String, Any?>, dryRun: Boolean): ScaffoldResult = gateway.scaffold(payload, dryRun)

  fun render(repoRoot: Path, skillName: String): ScaffoldRenderResult = gateway.render(repoRoot, skillName)
}

@Inject
class UnsupportedScaffoldService(
  private val gateway: UnsupportedScaffoldGateway,
) {
  fun retiredUnsupportedMessage(command: String, replacement: String, editor: Boolean): String =
    gateway.retiredUnsupportedMessage(command, replacement, editor)
}
