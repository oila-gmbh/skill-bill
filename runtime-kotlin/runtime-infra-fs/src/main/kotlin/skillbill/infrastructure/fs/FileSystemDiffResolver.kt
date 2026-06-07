package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diff.DiffResolverPort
import java.nio.file.Path

@Inject
class FileSystemDiffResolver : DiffResolverPort {
  override fun runProcess(args: List<String>, workDir: Path): String? = try {
    val process = ProcessBuilder(args)
      .directory(workDir.toFile())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    if (exitCode == 0) output else null
  } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
    null
  }
}
