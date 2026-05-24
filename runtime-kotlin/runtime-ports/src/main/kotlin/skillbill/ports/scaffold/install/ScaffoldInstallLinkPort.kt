package skillbill.ports.scaffold.install

import skillbill.ports.scaffold.install.model.ScaffoldInstallLinkRequest
import skillbill.ports.scaffold.install.model.ScaffoldInstallLinkResult
import java.nio.file.Path

/**
 * Capability port for applying install links to the agent install targets that a scaffolded
 * skill should appear in. Implementations re-use the existing install pipeline (`installSkill`)
 * inside `runtime-infra-fs`; pure-policy callers stay agnostic of agent detection.
 */
interface ScaffoldInstallLinkPort {
  /** Applies install links for the requested install paths. */
  fun applyInstallLinks(request: ScaffoldInstallLinkRequest): ScaffoldInstallLinkResult

  /** Removes previously applied install targets during rollback. */
  fun rollbackInstallTargets(installTargets: List<Path>)
}
