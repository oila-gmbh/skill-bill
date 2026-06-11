package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.CommandPaletteAction
import skillbill.desktop.core.domain.model.CommandPaletteResult
import skillbill.desktop.core.domain.model.CommandPaletteResultKind
import skillbill.desktop.core.domain.model.CommandPaletteState
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.SkillBillAcceleratorLabels
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
  val blockedByBusy = busyDisabledReason(state.busyOperation)
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
        acceleratorLabel = SkillBillAcceleratorLabels.REFRESH,
      ),
      keywords = listOf("refresh", "reload", "repository", "tree"),
      baseRank = COMMAND_BASE_RANK,
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
        acceleratorLabel = SkillBillAcceleratorLabels.SAVE,
      ),
      keywords = listOf("save", "editor", "authored", "content", "draft"),
      baseRank = COMMAND_BASE_RANK - 3,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.install-setup",
        title = "Install setup",
        subtitle = "Reopen the Skill Bill setup wizard",
        marker = "in",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.INSTALL_SETUP,
        disabledReason = blockedByBusy ?: installSetupDisabledReason(state),
      ),
      keywords = listOf("install", "setup", "wizard", "reinstall", "agents", "packs", "telemetry", "mcp"),
      baseRank = COMMAND_BASE_RANK - 5,
      sortGroup = 0,
    ),
    newScaffoldCandidate(
      id = "command.new-horizontal-skill",
      title = "New horizontal skill",
      subtitle = "Scaffold a new repo-wide horizontal skill",
      marker = "nh",
      action = CommandPaletteAction.NEW_HORIZONTAL_SKILL,
      blockedByBusy = blockedByBusy,
      state = state,
      keywords = listOf("new", "scaffold", "horizontal", "skill", "create"),
      rankOffset = -7,
    ),
    newScaffoldCandidate(
      id = "command.new-platform-pack",
      title = "New platform pack",
      subtitle = "Scaffold a new piloted platform pack",
      marker = "np",
      action = CommandPaletteAction.NEW_PLATFORM_PACK,
      blockedByBusy = blockedByBusy,
      state = state,
      keywords = listOf("new", "scaffold", "platform", "pack", "create"),
      rankOffset = -9,
    ),
    newScaffoldCandidate(
      id = "command.new-add-on",
      title = "New add-on",
      subtitle = "Scaffold a governed add-on under a platform pack",
      marker = "na",
      action = CommandPaletteAction.NEW_ADD_ON,
      blockedByBusy = blockedByBusy,
      state = state,
      keywords = listOf("new", "scaffold", "add-on", "addon", "create"),
      rankOffset = -10,
    ),
  )
}

private fun newScaffoldCandidate(
  id: String,
  title: String,
  subtitle: String,
  marker: String,
  action: CommandPaletteAction,
  blockedByBusy: String?,
  state: SkillBillState,
  keywords: List<String>,
  rankOffset: Int,
): PaletteCandidate = PaletteCandidate(
  result = CommandPaletteResult(
    id = id,
    title = title,
    subtitle = subtitle,
    marker = marker,
    kind = CommandPaletteResultKind.COMMAND,
    action = action,
    disabledReason = blockedByBusy ?: scaffoldDisabledReason(state),
  ),
  keywords = keywords,
  baseRank = COMMAND_BASE_RANK + rankOffset,
  sortGroup = 0,
)

private fun scaffoldDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  state.scaffoldWizard != null -> "Dismiss the current scaffold wizard first."
  else -> null
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

private fun busyDisabledReason(busyOperation: SkillBillBusyOperation?): String? = when {
  busyOperation != null -> "Wait for ${busyOperation.label()} to finish."
  else -> null
}

private fun saveDisabledReason(state: SkillBillState): String? = when {
  !state.editor.editable -> "Select editable authored content first."
  !state.editor.dirty -> "Make an editor change before saving."
  state.editor.saveInProgress -> "Wait for the current save to finish."
  else -> null
}

private fun installSetupDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  state.scaffoldWizard != null -> "Dismiss the current scaffold wizard first."
  state.firstRunSetup != null -> "Finish or dismiss the current setup wizard first."
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
  SkillBillBusyOperation.SAVE -> "save"
  SkillBillBusyOperation.SCAFFOLD -> "scaffold"
  SkillBillBusyOperation.FIRST_RUN_SETUP -> "setup"
  SkillBillBusyOperation.DELETE -> "delete"
  SkillBillBusyOperation.VALIDATE_AGENT_CONFIGS -> "validate agent configs"
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
