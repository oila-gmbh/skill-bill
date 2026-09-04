package skillbill.application.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult
import skillbill.ports.skillremove.SkillRemoveFileSystem

@Inject
class SkillRemoveService(
  private val fileSystem: SkillRemoveFileSystem,
) {
  fun previewRemoval(request: SkillRemovalRequest): SkillRemovalResult = SkillRemove(fileSystem).previewRemoval(request)

  fun executeRemoval(request: SkillRemovalRequest): SkillRemovalResult = SkillRemove(fileSystem).executeRemoval(request)
}
