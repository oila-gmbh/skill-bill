package skillbill.cli.team

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.team.TeamExportException
import skillbill.application.team.TeamExportService
import skillbill.application.team.TeamSyncService
import skillbill.cli.core.CliRunState
import skillbill.cli.core.DocumentedCliCommand
import skillbill.cli.core.DocumentedNoOpCliCommand
import skillbill.cli.core.formatOption
import skillbill.cli.install.InstallRequestCommand
import skillbill.cli.model.CliFormat
import skillbill.contracts.JsonSupport
import skillbill.error.ShellContentContractException
import skillbill.error.SkillBillRuntimeException
import skillbill.team.model.TeamBundleChannel
import skillbill.team.model.TeamExportRequest
import skillbill.team.model.TeamRollbackRequest
import skillbill.team.model.TeamStatusRequest
import skillbill.team.model.TeamSyncRequest
import java.nio.file.Path

@Inject
class TeamCommand(
  exportCommand: TeamExportCommand,
  syncCommand: TeamSyncCommand,
  rollbackCommand: TeamRollbackCommand,
  statusCommand: TeamStatusCommand,
) : DocumentedNoOpCliCommand("team", "Export and manage local team bundles.") {
  init {
    subcommands(exportCommand, syncCommand, rollbackCommand, statusCommand)
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
    .choice("development", "beta", "stable", "preview", "experimental")
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
      completeFailed(error.message.orEmpty())
    } catch (error: ShellContentContractException) {
      completeFailed(error.message.orEmpty())
    } catch (error: IllegalArgumentException) {
      completeFailed(error.message.orEmpty())
    }
  }

  private fun completeFailed(message: String) {
    val payload = mapOf("status" to "failed", "error" to message)
    if (format == CliFormat.JSON) {
      state.complete(payload, format, exitCode = 1)
    } else {
      state.completeText("$message\n", payload, exitCode = 1)
    }
  }
}

@Inject
class TeamSyncCommand(
  private val state: CliRunState,
  private val service: TeamSyncService,
) : InstallRequestCommand("sync", "Sync and install a governed team bundle.") {
  private val bundle by argument("bundle", help = "Bundle archive path.").optional()
  private val registry by option("--registry", help = "Local registry root to resolve from.")
  private val channel by option("--channel", help = "Registry channel.")
    .choice("development", "beta", "stable")

  override fun run() {
    try {
      val result = service.sync(
        TeamSyncRequest(
          bundlePath = bundle?.let(Path::of),
          registryRoot = registry?.let(Path::of),
          channel = channel?.let { requireNotNull(TeamBundleChannel.fromWireValue(it)) },
          installRequest = toRequest(state),
        ),
      )
      val payload = result.toPayload()
      if (format == CliFormat.JSON) {
        state.completeText(JsonSupport.mapToJsonString(payload) + "\n", payload)
      } else {
        state.completeText(
          "Team bundle synced: ${result.installed.bundleId} ${result.installed.version} " +
            "(${result.installed.channel.wireValue})\n",
          payload,
        )
      }
    } catch (error: ShellContentContractException) {
      completeFailed(error)
    } catch (error: SkillBillRuntimeException) {
      completeFailed(error)
    } catch (error: IllegalArgumentException) {
      completeFailed(error)
    }
  }

  private fun completeFailed(error: Throwable) {
    val payload = mapOf(
      "status" to "failed",
      "error_type" to error::class.simpleName.orEmpty(),
      "error" to error.message.orEmpty(),
    )
    if (format == CliFormat.JSON) {
      state.complete(payload, format, exitCode = 1)
    } else {
      state.completeText("${error.message.orEmpty()}\n", payload, exitCode = 1)
    }
  }
}

@Inject
class TeamRollbackCommand(
  private val state: CliRunState,
  private val service: TeamSyncService,
) : InstallRequestCommand("rollback", "Rollback to the previously installed team bundle.") {
  override fun run() {
    try {
      val result = service.rollback(TeamRollbackRequest(toRequest(state)))
      val payload = result.toPayload()
      if (format == CliFormat.JSON) {
        state.completeText(JsonSupport.mapToJsonString(payload) + "\n", payload)
      } else {
        state.completeText(
          "Team bundle rolled back: ${result.restored.bundleId} ${result.restored.version} " +
            "(${result.restored.channel.wireValue})\n",
          payload,
        )
      }
    } catch (error: ShellContentContractException) {
      completeFailed(error)
    } catch (error: SkillBillRuntimeException) {
      completeFailed(error)
    } catch (error: IllegalArgumentException) {
      completeFailed(error)
    }
  }

  private fun completeFailed(error: Throwable) {
    val payload = mapOf(
      "status" to "failed",
      "error_type" to error::class.simpleName.orEmpty(),
      "error" to error.message.orEmpty(),
    )
    if (format == CliFormat.JSON) {
      state.complete(payload, format, exitCode = 1)
    } else {
      state.completeText("${error.message.orEmpty()}\n", payload, exitCode = 1)
    }
  }
}

@Inject
class TeamStatusCommand(
  private val state: CliRunState,
  private val service: TeamSyncService,
) : DocumentedCliCommand("status", "Show the installed team bundle state.") {
  private val format by formatOption()

  override fun run() {
    try {
      val result = service.status(TeamStatusRequest(state.userHome))
      val payload = result.toPayload()
      if (format == CliFormat.JSON) {
        state.completeText(JsonSupport.mapToJsonString(payload) + "\n", payload)
      } else {
        val installed = result.installed
        state.completeText(
          if (installed == null) {
            "No team bundle installed.\n"
          } else {
            "Team bundle installed: ${installed.bundleId} ${installed.version} (${installed.channel.wireValue})\n"
          },
          payload,
        )
      }
    } catch (error: ShellContentContractException) {
      val payload = mapOf("status" to "failed", "error" to error.message.orEmpty())
      state.complete(payload, format, exitCode = 1)
    }
  }
}
