package skillbill.cli.team

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.team.TeamExportException
import skillbill.application.team.TeamExportService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.model.CliFormat
import skillbill.contracts.JsonSupport
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamExportRequest
import java.nio.file.Path

@Inject
class TeamCommand(
  exportCommand: TeamExportCommand,
) : DocumentedNoOpCliCommand("team", "Export and manage local team bundles.") {
  init {
    subcommands(exportCommand)
  }
}

@Inject
class TeamExportCommand(
  private val state: CliRunState,
  private val service: TeamExportService,
) : DocumentedCliCommand("export", "Export a deterministic team bundle archive.") {
  private val repoRoot by option("--repo-root", help = "Repository root to export.").default(".")
  private val version by option("--version", help = "Bundle version.").required()
  private val channel by option("--channel", help = "Bundle channel.")
    .choice("stable", "preview", "experimental")
    .required()
  private val output by option("--output", help = "Archive path to write.")
  private val registry by option("--registry", help = "Local registry root to publish into.")
  private val dryRun by option("--dry-run", help = "Validate and compute bundle metadata without writing.").flag()
  private val createdAt by option("--created-at", help = "Created-at timestamp for deterministic exports.")
  private val createdBy by option("--created-by", help = "Created-by metadata value.")
  private val sourceRepo by option("--source-repo", help = "Source repository metadata value.")
  private val sourceRef by option("--source-ref", help = "Source ref metadata value.").default("HEAD")
  private val sourceCommit by option("--source-commit", help = "Optional source commit metadata value.")
  private val format by formatOption()

  override fun run() {
    val request = TeamExportRequest(
      repoRoot = Path.of(repoRoot),
      version = version,
      channel = requireNotNull(TeamBundleChannel.fromWireValue(channel)),
      outputPath = output?.let(Path::of),
      registryRoot = registry?.let(Path::of),
      dryRun = dryRun,
      createdAt = createdAt ?: service.defaultCreatedAt(),
      createdBy = createdBy ?: state.environment["USER"].orEmpty().ifBlank { "skill-bill" },
      sourceRepo = sourceRepo ?: Path.of(repoRoot).toAbsolutePath().normalize().toString(),
      sourceRef = sourceRef,
      sourceCommit = sourceCommit,
    )
    try {
      val result = service.export(request)
      val payload = result.toPayload()
      if (format == CliFormat.JSON) {
        state.completeText(JsonSupport.mapToJsonString(payload) + "\n", payload)
      } else {
        state.completeText("Team bundle exported: ${result.bundlePath}\n", payload)
      }
    } catch (error: TeamExportException) {
      state.completeText(
        "${error.message}\n",
        mapOf("status" to "failed", "error" to error.message.orEmpty()),
        exitCode = 1,
      )
    } catch (error: IllegalArgumentException) {
      state.completeText(
        "${error.message}\n",
        mapOf("status" to "failed", "error" to error.message.orEmpty()),
        exitCode = 1,
      )
    }
  }
}
