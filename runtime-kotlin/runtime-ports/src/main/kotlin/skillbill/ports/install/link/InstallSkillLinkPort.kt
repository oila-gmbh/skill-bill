package skillbill.ports.install.link

import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.link.model.InstallSkillLinkResult

interface InstallSkillLinkPort {
  fun linkSkill(request: InstallSkillLinkRequest): InstallSkillLinkResult
}
