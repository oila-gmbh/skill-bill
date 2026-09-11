package skillbill.architecture

/** Expected JVM runtime module and subsystem graph the architecture tests assert against. */
object RuntimeModuleCatalog {
  val declaredGradleModules: List<String> =
    listOf(
      "runtime-application",
      "runtime-contracts",
      "runtime-core",
      "runtime-domain",
      "runtime-engine",
      "runtime-infra-fs",
      "runtime-infra-http",
      "runtime-infra-sqlite",
      "runtime-cli",
      "runtime-mcp",
      "runtime-ports",
    )

  val declaredSubsystemPackages: List<String> =
    listOf(
      "skillbill.agent.model",
      "skillbill.agentaddon",
      "skillbill.application",
      "skillbill.boundary",
      "skillbill.cli",
      "skillbill.config",
      "skillbill.contracts",
      "skillbill.di",
      "skillbill.domain.skillremove",
      "skillbill.engine",
      "skillbill.error",
      "skillbill.featurespec",
      "skillbill.goalrunner",
      "skillbill.idestatus",
      "skillbill.infrastructure",
      "skillbill.install",
      "skillbill.learnings",
      "skillbill.mcp",
      "skillbill.model",
      "skillbill.ports",
      "skillbill.review",
      "skillbill.scaffold",
      "skillbill.telemetry",
      "skillbill.text",
      "skillbill.workflow",
      "skillbill.workflow.verify",
    )

  val moduleMainPackageRoots: Map<String, String> =
    mapOf(
      "runtime-application" to "skillbill.application",
      "runtime-cli" to "skillbill.cli",
      "runtime-core" to "skillbill.di",
      "runtime-engine" to "skillbill.engine",
      "runtime-infra-fs" to "skillbill.infrastructure.fs",
      "runtime-infra-http" to "skillbill.infrastructure.http",
      "runtime-infra-sqlite" to "skillbill.infrastructure.sqlite",
      "runtime-mcp" to "skillbill.mcp",
    )
}
