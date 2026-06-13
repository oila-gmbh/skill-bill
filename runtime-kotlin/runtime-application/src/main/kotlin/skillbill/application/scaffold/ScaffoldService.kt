package skillbill.application.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.repoRoot
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import skillbill.ports.scaffold.catalog.model.ScaffoldExplainResult
import skillbill.ports.scaffold.catalog.model.ScaffoldListResult
import skillbill.ports.scaffold.catalog.model.ScaffoldShowResult
import skillbill.ports.scaffold.model.ScaffoldRenderResult
import skillbill.ports.scaffold.repo.model.ScaffoldUpgradeResult
import skillbill.ports.scaffold.repo.model.ScaffoldValidateResult
import skillbill.ports.scaffold.source.model.ScaffoldEditWithBodyFileResult
import skillbill.ports.scaffold.source.model.ScaffoldFillResult
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Path

/**
 * SKILL-52.1 subtask 3: every raw-map producer on the gateway has been replaced with a
 * typed result model under `skillbill.ports.scaffold.*.model`. This service is now a
 * pure pass-through, mirroring `WorkflowService` and `LearningResultsService`. The CLI
 * adapter performs the JSON wire-shape conversion via `ScaffoldCliResultMappers`; no
 * wire-mapping logic lives here (wire-mapper triplication pitfall). The MCP runtime
 * does not currently expose the raw-map scaffold endpoints, so it has no parallel
 * mapper file yet (F-004); when it gains one, the mapper will be reintroduced
 * alongside that wiring.
 */
@Inject
class ScaffoldService(
  private val gateway: ScaffoldGateway,
) {
  fun list(repoRoot: Path, skillNames: List<String>): ScaffoldListResult = gateway.list(repoRoot, skillNames)

  fun show(repoRoot: Path, skillName: String, contentMode: String): ScaffoldShowResult =
    gateway.show(repoRoot, skillName, contentMode)

  fun explain(repoRoot: Path, skillName: String?): ScaffoldExplainResult = gateway.explain(repoRoot, skillName)

  fun validate(repoRoot: Path, skillNames: List<String>): ScaffoldValidateResult =
    gateway.validate(repoRoot, skillNames)

  fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean): ScaffoldUpgradeResult =
    gateway.upgrade(repoRoot, skillNames, validate)

  fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?): ScaffoldFillResult =
    gateway.fill(repoRoot, skillName, body, sectionName)

  fun saveExactContent(repoRoot: Path, skillName: String, content: String): ScaffoldSaveExactContentResult =
    gateway.saveExactContent(repoRoot, skillName, content)

  fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): ScaffoldEditWithBodyFileResult = gateway.editWithBodyFile(repoRoot, skillName, body, sectionName)

  /**
   * SKILL-52.2 subtask 2: typed scaffold entry point. CLI / MCP / Desktop adapters parse their
   * wire payloads into a [ScaffoldCommandRequest] at the adapter boundary and call this method
   * directly — the public application surface no longer accepts a raw `Map<String, Any?>`.
   */
  fun scaffold(request: ScaffoldCommandRequest, dryRun: Boolean): ScaffoldResult = gateway.scaffold(request, dryRun)

  fun render(repoRoot: Path, skillName: String): ScaffoldRenderResult = gateway.render(repoRoot, skillName)
}

@Inject
class UnsupportedScaffoldService(
  private val gateway: UnsupportedScaffoldGateway,
) {
  fun retiredUnsupportedMessage(command: String, replacement: String, editor: Boolean): String =
    gateway.retiredUnsupportedMessage(command, replacement, editor)
}
