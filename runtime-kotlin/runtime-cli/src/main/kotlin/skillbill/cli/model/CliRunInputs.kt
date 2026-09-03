package skillbill.cli.model

import java.nio.file.Path

data class CliRunInputs(
  val dbPathOverride: String?,
  val stdinText: String?,
  val environment: Map<String, String>,
  val externalCommandRunner: ExternalCommandRunner,
  val userHome: Path,
  val repositoryRoot: Path,
  val liveStdout: (String) -> Unit,
  val liveStderr: (String) -> Unit,
)
