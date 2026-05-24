package skillbill

/** Declaration of the enforced JVM runtime module and subsystem graph. */
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
      "runtime-desktop:core:database",
      "runtime-desktop:core:datastore",
      "runtime-desktop:core:designsystem",
      "runtime-desktop:core:domain",
      "runtime-desktop:core:navigation",
      "runtime-desktop:core:testing",
      "runtime-desktop:core:ui",
      "runtime-desktop:feature:skillbill",
      "runtime-mcp",
      "runtime-ports",
    )

  val declaredSubsystemPackages: List<String> =
    listOf(
      "skillbill.application",
      "skillbill.boundary",
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
      "skillbill.domain.skillremove",
      "skillbill.install",
      "skillbill.nativeagent",
      "skillbill.error",
      "skillbill.skillremove",
      "skillbill.workflow",
    )
}
