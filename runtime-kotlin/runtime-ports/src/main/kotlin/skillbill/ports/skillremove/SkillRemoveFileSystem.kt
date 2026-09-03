package skillbill.ports.skillremove

import skillbill.domain.skillremove.model.AgentSymlinkUnlink
import skillbill.domain.skillremove.model.AppliedCascade
import skillbill.domain.skillremove.model.ManifestEdit
import skillbill.domain.skillremove.model.ReadmeCatalogEdit
import skillbill.domain.skillremove.model.SkillRemovalPreview
import skillbill.domain.skillremove.model.SkillRemovalRequest

interface SkillRemoveFileSystem {
  fun resolveCascadeFilesystemPaths(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<String>

  fun discoverCascadedSkillNames(request: SkillRemovalRequest): List<String>

  fun targetExists(request: SkillRemovalRequest): Boolean

  fun planManifestEdits(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<ManifestEdit>

  fun planAgentSymlinkUnlinks(request: SkillRemovalRequest, cascadedSkillNames: List<String>): List<AgentSymlinkUnlink>

  fun planReadmeCatalogEdits(request: SkillRemovalRequest): List<ReadmeCatalogEdit>

  fun applyCascade(request: SkillRemovalRequest, preview: SkillRemovalPreview): AppliedCascade
}
