package skillbill.ports.diff

import java.nio.file.Path

interface DiffResolverPort {
  fun runProcess(args: List<String>, workDir: Path): String?
}
