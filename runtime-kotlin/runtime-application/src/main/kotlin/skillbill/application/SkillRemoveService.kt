package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.domain.skillremove.SkillRemove
import skillbill.domain.skillremove.SkillRemoveFileSystem
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult

@Inject
class SkillRemoveService(
  private val fileSystem: SkillRemoveFileSystem,
) {
  fun previewRemoval(request: SkillRemovalRequest): SkillRemovalResult = SkillRemove(fileSystem).previewRemoval(request)

  fun executeRemoval(request: SkillRemovalRequest): SkillRemovalResult = SkillRemove(fileSystem).executeRemoval(request)
}
