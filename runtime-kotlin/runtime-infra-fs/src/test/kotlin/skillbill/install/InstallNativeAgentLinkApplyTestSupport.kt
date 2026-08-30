package skillbill.install

import skillbill.model.EnvironmentContext
import java.nio.file.Path

open class InstallNativeAgentLinkApplyTestSupport : InstallApplyTestSupport() {
  protected fun inventoryJson(logicalName: String, installedPath: Path, cacheTargetPath: Path, sourceRoot: Path) = """
    {"contract_version":"0.2","entries":[
      {"logical_name":"$logicalName","provider":"codex","installed_path":"$installedPath",
        "cache_target_path":"$cacheTargetPath","content_digest":"${"0".repeat(
    64,
  )}","source_root":"$sourceRoot"}
    ]}
  """.trimIndent()

  protected fun preflightContext(home: Path): EnvironmentContext = EnvironmentContext(
    userHome = home,
    environment = installTestEnvironment(home),
  )
}
