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
  val publishingBusy = state.publishBusy || state.commitBusy || state.commitValidationRunning || state.pushBusy
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
        acceleratorLabel = SkillBillAcceleratorLabels.REFRESH,
      ),
      keywords = listOf("refresh", "reload", "repository", "tree"),
      baseRank = COMMAND_BASE_RANK,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.validate",
        title = "Validate all",
        subtitle = "Run full Skill Bill repository validation",
        marker = "ok",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.VALIDATE,
        disabledReason = blockedByBusy ?: validateDisabledReason(state),
        acceleratorLabel = SkillBillAcceleratorLabels.VALIDATE,
      ),
      keywords = listOf("validate", "validation", "check", "contract"),
      baseRank = COMMAND_BASE_RANK - 1,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.validate-selected",
        title = "Validate selected",
        subtitle = "Run validation for the current skill",
        marker = "vs",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.VALIDATE_SELECTED,
        disabledReason = blockedByBusy ?: validateSelectedDisabledReason(state),
      ),
      keywords = listOf("validate", "validation", "selected", "skill", "check", "contract"),
      baseRank = COMMAND_BASE_RANK - 2,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.render",
        title = "Render selected",
        subtitle = "Render generated runtime artifacts for the current selection",
        marker = "rc",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.RENDER,
        disabledReason = blockedByBusy ?: renderDisabledReason(state),
        acceleratorLabel = SkillBillAcceleratorLabels.RENDER,
      ),
      keywords = listOf("render", "console", "generated", "artifact", "check"),
      baseRank = COMMAND_BASE_RANK - 3,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.render-all",
        title = "Render all",
        subtitle = "Render generated runtime artifacts for every renderable source",
        marker = "ra",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.RENDER_ALL,
        disabledReason = blockedByBusy ?: renderAllDisabledReason(state),
      ),
      keywords = listOf("render", "all", "full", "repo", "repository", "console", "generated", "artifact", "check"),
      baseRank = COMMAND_BASE_RANK - 4,
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
        acceleratorLabel = SkillBillAcceleratorLabels.SAVE,
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
      id = "command.new-platform-override",
      title = "New platform override",
      subtitle = "Scaffold a platform-piloted family override",
      marker = "no",
      action = CommandPaletteAction.NEW_PLATFORM_OVERRIDE,
      blockedByBusy = blockedByBusy,
      state = state,
      keywords = listOf("new", "scaffold", "platform", "override", "family", "create"),
      rankOffset = -10,
    ),
    newScaffoldCandidate(
      id = "command.new-code-review-area",
      title = "New code-review area",
      subtitle = "Scaffold an approved code-review specialist area",
      marker = "nc",
      action = CommandPaletteAction.NEW_CODE_REVIEW_AREA,
      blockedByBusy = blockedByBusy,
      state = state,
      keywords = listOf("new", "scaffold", "code-review", "area", "specialist", "create"),
      rankOffset = -11,
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
      rankOffset = -12,
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

private fun validateSelectedDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  state.selectedTreeItemId == null -> "Select a skill first."
  !state.treeItems.isSelectedSkill(state.selectedTreeItemId) -> "The current selection is not a skill."
  else -> null
}

private fun renderDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  state.selectedTreeItemId == null -> "Select a renderable skill, add-on, or native agent first."
  !state.renderable -> "The current selection cannot be rendered."
  else -> null
}

private fun renderAllDisabledReason(state: SkillBillState): String? = when {
  state.selectedRepoPath == null -> "Open a Skill Bill repository first."
  state.repoStatus.state != RepoLoadState.LOADED -> "Open a valid Skill Bill repository first."
  !state.treeItems.hasRenderableTreeItem() -> "No renderable skills, add-ons, or native agents were found."
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
  SkillBillBusyOperation.VALIDATE -> "validation"
  SkillBillBusyOperation.RENDER -> "render"
  SkillBillBusyOperation.SAVE -> "save"
  SkillBillBusyOperation.SCAFFOLD -> "scaffold"
  SkillBillBusyOperation.FIRST_RUN_SETUP -> "setup"
}

private fun List<SkillBillTreeItem>.flattenPaletteTree(): List<SkillBillTreeItem> =
  flatMap { item -> listOf(item) + item.children.flattenPaletteTree() }

private fun List<SkillBillTreeItem>.hasRenderableTreeItem(): Boolean =
  any { item -> item.kind.isRenderableTreeItemKind() || item.children.hasRenderableTreeItem() }

private fun List<SkillBillTreeItem>.isSelectedSkill(selectedTreeItemId: String?): Boolean = any { item ->
  (item.id == selectedTreeItemId && item.kind == TreeItemKind.SKILL) ||
    item.children.isSelectedSkill(selectedTreeItemId)
}

private fun TreeItemKind.isRenderableTreeItemKind(): Boolean = when (this) {
  TreeItemKind.SKILL,
  TreeItemKind.ADD_ON,
  TreeItemKind.NATIVE_AGENT,
  -> true
  TreeItemKind.GROUP,
  TreeItemKind.PLATFORM_PACK,
  TreeItemKind.GENERATED_ARTIFACT,
  TreeItemKind.PLACEHOLDER,
  -> false
}

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
