package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.domain.skillremove.SkillRemoveFileSystem
import skillbill.model.RuntimeContext
import skillbill.skillremove.SkillRemoveJvmFileSystem

@Inject
class FileSystemSkillRemoveFileSystem(
  context: RuntimeContext,
) : SkillRemoveFileSystem by SkillRemoveJvmFileSystem(home = context.userHome)
