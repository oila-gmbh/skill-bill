package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.service.RepoSessionService
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.Path

@Inject
@SingleIn(UserScope::class)
class RuntimeRepoSessionService(
  private val runtimeServices: DesktopRuntimeApplicationServices =
    DesktopRuntimeApplicationServices.forCurrentUserHome(),
  private val store: RepoBrowserStore = RepoBrowserStore(runtimeServices),
) : RepoSessionService {
  private val repoValidationService get() = runtimeServices.repoValidationService
  private val treeBuilder = SkillTreeBuilder(runtimeServices)

  override fun open(repoPath: String): RepoSession {
    val rawRepoPath = repoPath.trim()
    val root =
      resolveRepoPath(repoPath)
        ?: return invalidSession(rawRepoPath, "Select a Skill Bill repository path.").also { session ->
          store.snapshot = RepoBrowserSnapshot(loadStatus = session.loadStatus)
        }
    val status = validateRoot(root)
    if (status.state != RepoLoadState.LOADED) {
      store.snapshot = RepoBrowserSnapshot(repoRoot = root, loadStatus = status)
      return RepoSession(
        repoPath = root.toString(),
        isRecognizedSkillBillRepo = false,
        loadStatus = status,
      )
    }

    val tree =
      runCatching {
        treeBuilder.buildTree(root, store.baselineModifiedResolver, store.externalAddonSourcesResolver)
      }
        .getOrElse { error ->
          val invalidStatus =
            status.copy(
              state = RepoLoadState.INVALID,
              message = "Could not load Skill Bill repo tree: ${error.message.orEmpty()}",
            )
          store.snapshot = RepoBrowserSnapshot(repoRoot = root, loadStatus = invalidStatus)
          return RepoSession(
            repoPath = root.toString(),
            isRecognizedSkillBillRepo = false,
            loadStatus = invalidStatus,
          )
        }
    store.snapshot =
      RepoBrowserSnapshot(
        repoRoot = root,
        repoToken = repoToken(root),
        loadStatus = status,
        treeItems = tree.items,
        selections = tree.selections,
      )
    return RepoSession(
      repoPath = root.toString(),
      isRecognizedSkillBillRepo = true,
      loadStatus = status,
    )
  }

  private fun validateRoot(root: Path): RepoLoadStatus {
    if (!Files.isDirectory(root)) {
      return RepoLoadStatus(
        state = RepoLoadState.INVALID,
        message = "Selected path is not a directory: $root",
      )
    }
    if (!looksLikeSkillBillRepo(root)) {
      return RepoLoadStatus(
        state = RepoLoadState.INVALID,
        message = "Selected directory is not a Skill Bill repo. Expected skills/ or platform-packs/ sources.",
      )
    }

    return runCatching { repoValidationService.validateRepo(root) }
      .fold(
        onSuccess = { report ->
          RepoLoadStatus(
            state = RepoLoadState.LOADED,
            message =
            if (report.passed) {
              "Skill Bill repo loaded."
            } else {
              "Skill Bill repo loaded with validation issues."
            },
            issueCount = report.issues.size,
            skillCount = report.skillCount,
            addonCount = report.addonCount,
            platformPackCount = report.platformPackCount,
            nativeAgentCount = report.nativeAgentCount,
            issues = report.issues,
          )
        },
        onFailure = { error ->
          RepoLoadStatus(
            state = RepoLoadState.INVALID,
            message = "Could not validate Skill Bill repo: ${error.message.orEmpty()}",
          )
        },
      )
  }
}
