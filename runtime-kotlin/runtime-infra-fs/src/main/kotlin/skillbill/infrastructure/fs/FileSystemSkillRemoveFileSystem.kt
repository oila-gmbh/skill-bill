package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.domain.skillremove.SkillRemoveFileSystem
import skillbill.model.EnvironmentContext
import skillbill.skillremove.SkillRemoveJvmFileSystem

@Inject
class FileSystemSkillRemoveFileSystem(
  context: EnvironmentContext,
) : SkillRemoveFileSystem by SkillRemoveJvmFileSystem(home = context.userHome)
