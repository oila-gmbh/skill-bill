package skillbill.architecture

/** Expected JVM runtime module and subsystem graph the architecture tests assert against. */
object RuntimeModuleCatalog {
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
      "skillbill.featurespec",
      "skillbill.goalplanning",
      "skillbill.goalrunner",
      "skillbill.skillremove",
      "skillbill.workflow",
    )
}
