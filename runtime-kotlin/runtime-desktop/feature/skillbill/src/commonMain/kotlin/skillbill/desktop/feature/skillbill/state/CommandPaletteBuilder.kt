package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.CommandPaletteAction
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.CommandPaletteResultKind
import skillbill.desktop.core.domain.model.CommandPaletteState
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.SkillBillBusyOperation
import skillbill.desktop.core.domain.model.SkillBillState
import skillbill.desktop.core.domain.model.SkillBillTreeItem
import skillbill.desktop.core.domain.model.TreeItemKind

internal fun buildCommandPaletteState(
  state: SkillBillState,
  open: Boolean,
  query: String,
  selectedResultIndex: Int,
): CommandPaletteState {
  val rankedResults = commandPaletteCandidates(state)
    .mapNotNull { candidate -> candidate.toRankedResult(query) }
    .sortedWith(
      compareByDescending<RankedPaletteResult> { it.result.rank }
        .thenBy { it.sortGroup }
        .thenBy { it.result.title.lowercase() },
    )
    .map { it.result }
  val clampedSelection =
    if (rankedResults.isEmpty()) {
      0
    } else {
      selectedResultIndex.coerceIn(0, rankedResults.lastIndex)
    }
  return CommandPaletteState(
    open = open,
    query = query,
    selectedResultIndex = clampedSelection,
    results = rankedResults,
  )
}

private fun commandPaletteCandidates(state: SkillBillState): List<PaletteCandidate> {
  val publishingBusy = state.commitBusy || state.commitValidationRunning || state.pushBusy
  val blockedByBusy = busyDisabledReason(state.busyOperation, publishingBusy)
  return commandCandidates(state, blockedByBusy) + treeCandidates(state, blockedByBusy)
}

private fun commandCandidates(state: SkillBillState, blockedByBusy: String?): List<PaletteCandidate> {
  val openRepository = PaletteCandidate(
    result = CommandPaletteResult(
      id = "command.open-repository",
      title = "Open repository",
      subtitle = "Choose a local Skill Bill checkout",
      marker = "op",
      kind = CommandPaletteResultKind.COMMAND,
      action = CommandPaletteAction.OPEN_REPOSITORY,
      disabledReason = blockedByBusy,
    ),
    keywords = listOf("open", "repository", "repo", "choose", "directory", "checkout"),
    baseRank = COMMAND_BASE_RANK - 8,
    sortGroup = 0,
  )
  if (state.selectedRepoPath == null && state.repoStatus.state == RepoLoadState.EMPTY) {
    return listOf(openRepository)
  }
  return listOf(
    openRepository,
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.refresh",
        title = "Refresh",
        subtitle = "Reload repository tree",
        marker = "rf",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.REFRESH,
        disabledReason = blockedByBusy,
      ),
      keywords = listOf("refresh", "reload", "repository", "tree"),
      baseRank = COMMAND_BASE_RANK,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.validate",
        title = "Validate",
        subtitle = "Run Skill Bill validation",
        marker = "ok",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.VALIDATE,
        disabledReason = blockedByBusy ?: validateDisabledReason(state),
      ),
      keywords = listOf("validate", "validation", "check", "contract"),
      baseRank = COMMAND_BASE_RANK - 1,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.render",
        title = "Render check",
        subtitle = "Render generated runtime artifacts for the current selection",
        marker = "rc",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.RENDER,
        disabledReason = blockedByBusy ?: renderDisabledReason(state),
      ),
      keywords = listOf("render", "console", "generated", "artifact", "check"),
      baseRank = COMMAND_BASE_RANK - 2,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.show-changes",
        title = "Show changes",
        subtitle = "Open the source-control changes dock",
        marker = "chg",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.SHOW_CHANGES,
        disabledReason = blockedByBusy ?: dockDisabledReason(state),
      ),
      keywords = listOf("show", "changes", "source", "control", "dock", "git", "status"),
      baseRank = COMMAND_BASE_RANK - 5,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.show-history",
        title = "Show history",
        subtitle = "Open the commit history dock",
        marker = "hst",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.SHOW_HISTORY,
        disabledReason = blockedByBusy ?: dockDisabledReason(state),
      ),
      keywords = listOf("show", "history", "commits", "dock", "git", "log"),
      baseRank = COMMAND_BASE_RANK - 6,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.save",
        title = "Save",
        subtitle = "Save the authored content editor",
        marker = "sv",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.SAVE,
        disabledReason = blockedByBusy ?: saveDisabledReason(state),
      ),
      keywords = listOf("save", "editor", "authored", "content", "draft"),
      baseRank = COMMAND_BASE_RANK - 3,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.refresh-git",
        title = "Refresh Git status",
        subtitle = "Reload changes, branch, and publishing status",
        marker = "git",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.REFRESH_GIT_STATUS,
        disabledReason = blockedByBusy ?: gitRefreshDisabledReason(state),
      ),
      keywords = listOf("git", "status", "changes", "source", "control", "refresh"),
      baseRank = COMMAND_BASE_RANK - 4,
      sortGroup = 0,
    ),
  )
}

private fun treeCandidates(state: SkillBillState, blockedByBusy: String?): List<PaletteCandidate> =
  state.treeItems.flattenPaletteTree().mapIndexed { index, item ->
    PaletteCandidate(
      result = CommandPaletteResult(
        id = item.id,
        title = item.label,
        subtitle = treeSubtitle(item),
        marker = treeMarker(item.kind),
        kind = CommandPaletteResultKind.TREE_ITEM,
        action = CommandPaletteAction.SELECT_TREE_ITEM,
        treeItemId = item.id,
        disabledReason = blockedByBusy,
      ),
      keywords = listOfNotNull(
        item.label,
        item.id,
        item.kind.name,
        item.authoredPath,
        item.status,
        item.metadata?.skillName,
        item.metadata?.kind,
        item.metadata?.packageName,
        item.metadata?.platform,
        item.metadata?.family,
        item.metadata?.area,
      ),
      baseRank = TREE_BASE_RANK - index,
      sortGroup = 1,
    )
  }

private fun busyDisabledReason(busyOperation: SkillBillBusyOperation?, publishingBusy: Boolean): String? = when {
  busyOperation != null -> "Wait for ${busyOperation.label()} to finish."
  publishingBusy -> "Wait for the publishing operation to finish."
  else -> null
}

private fun validateDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  else -> null
}

private fun renderDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  state.selectedTreeItemId == null -> "Select a renderable skill, add-on, or native agent first."
  !state.renderable -> "The current selection cannot be rendered."
  else -> null
}

private fun saveDisabledReason(state: SkillBillState): String? = when {
  !state.editor.editable -> "Select editable authored content first."
  !state.editor.dirty -> "Make an editor change before saving."
  state.editor.saveInProgress -> "Wait for the current save to finish."
  state.changesBusy -> "Wait for Git status refresh to finish."
  else -> null
}

private fun dockDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  else -> null
}

private fun gitRefreshDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  state.changesBusy -> "Wait for Git status refresh to finish."
  else -> null
}

private fun PaletteCandidate.toRankedResult(query: String): RankedPaletteResult? {
  val score = score(query, result.title, result.subtitle, result.id, keywords)
    ?: return null
  return RankedPaletteResult(
    result = result.copy(rank = baseRank + score),
    sortGroup = sortGroup,
  )
}

private fun score(query: String, title: String, subtitle: String, id: String, keywords: List<String>): Int? {
  val normalizedQuery = query.trim().lowercase()
  if (normalizedQuery.isBlank()) {
    return 0
  }
  val searchable = (listOf(title, subtitle, id) + keywords).map { it.lowercase() }
  val tokens = normalizedQuery.split(Regex("\\s+")).filter(String::isNotBlank)
  if (tokens.isEmpty()) {
    return 0
  }
  val tokenScore = tokens.sumOf { token ->
    searchable.maxOfOrNull { text -> scoreToken(token, text) } ?: 0
  }
  return tokenScore.takeIf { it > 0 && tokens.all { token -> searchable.any { text -> scoreToken(token, text) > 0 } } }
}

private fun scoreToken(token: String, text: String): Int = when {
  text == token -> 240
  text.startsWith(token) -> 180
  text.split('-', '_', '/', ' ').any { part -> part.startsWith(token) } -> 150
  text.contains(token) -> 100
  text.matchesSubsequence(token) -> 30
  else -> 0
}

private fun String.matchesSubsequence(query: String): Boolean {
  var queryIndex = 0
  for (character in this) {
    if (queryIndex < query.length && character == query[queryIndex]) {
      queryIndex += 1
    }
  }
  return queryIndex == query.length
}

private fun treeSubtitle(item: SkillBillTreeItem): String {
  val metadata = listOfNotNull(
    item.authoredPath,
    item.metadata?.skillName,
    item.metadata?.platform,
    item.metadata?.family,
    item.metadata?.area,
  ).firstOrNull()
  return metadata ?: item.kind.name.lowercase().replace('_', ' ')
}

private fun treeMarker(kind: TreeItemKind): String = when (kind) {
  TreeItemKind.GROUP -> "grp"
  TreeItemKind.SKILL -> "sk"
  TreeItemKind.PLATFORM_PACK -> "pk"
  TreeItemKind.ADD_ON -> "ad"
  TreeItemKind.NATIVE_AGENT -> "ag"
  TreeItemKind.GENERATED_ARTIFACT -> "gen"
  TreeItemKind.PLACEHOLDER -> "pl"
}

private fun SkillBillBusyOperation.label(): String = when (this) {
  SkillBillBusyOperation.OPEN_REPO -> "repository open"
  SkillBillBusyOperation.REFRESH -> "refresh"
  SkillBillBusyOperation.CHOOSE_DIRECTORY -> "directory selection"
  SkillBillBusyOperation.VALIDATE -> "validation"
  SkillBillBusyOperation.RENDER -> "render"
  SkillBillBusyOperation.SAVE -> "save"
}

private fun List<SkillBillTreeItem>.flattenPaletteTree(): List<SkillBillTreeItem> =
  flatMap { item -> listOf(item) + item.children.flattenPaletteTree() }

private data class PaletteCandidate(
  val result: CommandPaletteResult,
  val keywords: List<String>,
  val baseRank: Int,
  val sortGroup: Int,
)

private data class RankedPaletteResult(
  val result: CommandPaletteResult,
  val sortGroup: Int,
)

private const val COMMAND_BASE_RANK = 10_000
private const val TREE_BASE_RANK = 5_000
