package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.AuthoredContentDocument
import skillbill.desktop.core.domain.model.AuthoringSaveResult
import skillbill.desktop.core.domain.model.EditorPlaceholder
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.service.AuthoringGateway
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(UserScope::class)
class RuntimeAuthoringGateway(
  private val store: RepoBrowserStore,
) : AuthoringGateway {
  override fun describeSelection(treeItemId: String): EditorPlaceholder = store.snapshot.selections[treeItemId]
    ?.takeIf { detail -> detail.repoToken == store.snapshot.repoToken }
    ?.toEditorPlaceholder()
    ?: EditorPlaceholder.empty

  override fun loadDocument(session: RepoSession?, treeItemId: String?): AuthoredContentDocument {
    val detail = store.selectionFor(session, treeItemId)
      ?: return AuthoredContentDocument(
        treeItemId = treeItemId,
        title = "No source selected",
        skillName = null,
        kind = null,
        authoredPath = null,
        text = "",
        editable = false,
        readOnlyReason = "No editable governed source is selected.",
      )
    return detail.toDocument(treeItemId)
  }

  override fun saveDocument(session: RepoSession?, treeItemId: String?, body: String): AuthoringSaveResult {
    val root = store.snapshot.repoRoot
      ?: return AuthoringSaveResult.failed("No Skill Bill repository is open.")
    if (session?.isRecognizedSkillBillRepo != true || session.repoPath != root.toString()) {
      return AuthoringSaveResult.failed("No editable Skill Bill repository is open.")
    }
    val detail = store.selectionFor(session, treeItemId)
      ?: return AuthoringSaveResult.failed("No editable governed source is selected.")
    if (!detail.canEdit()) {
      return AuthoringSaveResult.failed(detail.readOnlyReasonForDocument())
    }
    return runCatching {
      val skillName = detail.skillName
      if (skillName != null) {
        store.authoringSaver(root, skillName, body)
      } else if (detail.kind == "add-on") {
        store.sourceFileSaver(detail.contentFile ?: error("Editable add-on selection is missing a source file."), body)
      } else {
        error("Only governed content.md files and add-ons can be saved through authoring.")
      }
      AuthoringSaveResult(
        success = true,
        document = detail.toDocument(treeItemId),
      )
    }.getOrElse { error ->
      AuthoringSaveResult.failed(runtimeMessage(error))
    }
  }
}
