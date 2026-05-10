package skillbill

/** Phase-1 declaration of the JVM runtime scaffold. */
object RuntimeModule {
  const val NAME: String = "runtime-kotlin"
  const val TOOLCHAIN_JDK: Int = 17

  val declaredGradleModules: List<String> =
    listOf(
      "runtime-application",
      "runtime-contracts",
      "runtime-core",
      "runtime-domain",
      "runtime-infra-fs",
      "runtime-infra-http",
      "runtime-infra-sqlite",
      "runtime-cli",
      "runtime-desktop",
      "runtime-desktop:core:common",
      "runtime-desktop:core:data",
      "runtime-desktop:core:designsystem",
      "runtime-desktop:core:domain",
      "runtime-desktop:core:testing",
      "runtime-desktop:core:ui",
      "runtime-desktop:feature:workbench",
      "runtime-mcp",
      "runtime-ports",
    )

  val declaredSubsystemPackages: List<String> =
    listOf(
      "skillbill.application",
      "skillbill.cli",
      "skillbill.desktop",
      "skillbill.di",
      "skillbill.launcher",
      "skillbill.mcp",
      "skillbill.model",
      "skillbill.db",
      "skillbill.telemetry",
      "skillbill.review",
      "skillbill.learnings",
      "skillbill.ports",
      "skillbill.infrastructure",
      "skillbill.workflow.implement",
      "skillbill.workflow.verify",
      "skillbill.scaffold",
      "skillbill.contracts",
      "skillbill.install",
      "skillbill.nativeagent",
      "skillbill.error",
    )
}
