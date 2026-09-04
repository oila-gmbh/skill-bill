package skillbill.skillremove

import me.tatarka.inject.annotations.Inject
import skillbill.model.EnvironmentContext
import skillbill.ports.skillremove.SkillRemoveFileSystem

@Inject
class FileSystemSkillRemoveFileSystem(
  context: EnvironmentContext,
) : SkillRemoveFileSystem by SkillRemoveJvmFileSystem(home = context.userHome)
