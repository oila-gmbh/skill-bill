package skillbill.desktop.core.data.service

import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.install.model.ExternalAddonSource
import skillbill.ports.scaffold.source.model.ScaffoldSaveExactContentResult
import java.nio.file.Path

class RuntimeRepoBrowserService(
  runtimeServices: DesktopRuntimeApplicationServices =
    DesktopRuntimeApplicationServices.forCurrentUserHome(),
) :
  RepoSessionService,
  SkillTreeService,
  AuthoringGateway {
  private val store = RepoBrowserStore(runtimeServices)
  private val repoSessionService = RuntimeRepoSessionService(runtimeServices, store)
  private val skillTreeService = RuntimeSkillTreeService(store)
  private val authoringGateway = RuntimeAuthoringGateway(store)

  internal var authoringSaver: (Path, String, String) -> ScaffoldSaveExactContentResult
    get() = store.authoringSaver
    set(value) {
      store.authoringSaver = value
    }

  internal var sourceFileSaver: (Path, String) -> Unit
    get() = store.sourceFileSaver
    set(value) {
      store.sourceFileSaver = value
    }

  internal var baselineModifiedResolver: (Path) -> Set<String>
    get() = store.baselineModifiedResolver
    set(value) {
      store.baselineModifiedResolver = value
    }

  internal var externalAddonSourcesResolver: () -> List<ExternalAddonSource>
    get() = store.externalAddonSourcesResolver
    set(value) {
      store.externalAddonSourcesResolver = value
    }

  override fun open(repoPath: String): RepoSession = repoSessionService.open(repoPath)

  override fun treeFor(session: RepoSession?): List<SkillBillTreeItem> = skillTreeService.treeFor(session)

  override fun describeSelection(treeItemId: String): EditorPlaceholder = authoringGateway.describeSelection(treeItemId)

  override fun resolveGeneratedArtifactTreeItemId(session: RepoSession?, artifactPath: String): String? =
    skillTreeService.resolveGeneratedArtifactTreeItemId(session, artifactPath)

  override fun loadDocument(session: RepoSession?, treeItemId: String?): AuthoredContentDocument =
    authoringGateway.loadDocument(session, treeItemId)

  override fun saveDocument(session: RepoSession?, treeItemId: String?, body: String): AuthoringSaveResult =
    authoringGateway.saveDocument(session, treeItemId, body)
}
