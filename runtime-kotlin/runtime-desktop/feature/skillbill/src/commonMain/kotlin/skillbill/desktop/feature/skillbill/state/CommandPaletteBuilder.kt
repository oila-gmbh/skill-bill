package skillbill.desktop.feature.skillbill.state

import dev.skillbill.designsystem.generated.resources.Res
import dev.skillbill.designsystem.generated.resources.accelerator_refresh
import dev.skillbill.designsystem.generated.resources.accelerator_save
import dev.skillbill.designsystem.generated.resources.command_disabled_invalid_repo
import dev.skillbill.designsystem.generated.resources.command_disabled_no_repo
import dev.skillbill.designsystem.generated.resources.command_disabled_not_dirty
import dev.skillbill.designsystem.generated.resources.command_disabled_not_editable
import dev.skillbill.designsystem.generated.resources.command_disabled_save_in_progress
import dev.skillbill.designsystem.generated.resources.command_disabled_scaffold_wizard_open
import dev.skillbill.designsystem.generated.resources.command_disabled_setup_wizard_open
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_delete
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_directory
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_refresh
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_repo_open
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_save
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_scaffold
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_setup
import dev.skillbill.designsystem.generated.resources.command_disabled_wait_for_validate
import dev.skillbill.designsystem.generated.resources.command_install_setup
import dev.skillbill.designsystem.generated.resources.command_install_setup_subtitle
import dev.skillbill.designsystem.generated.resources.command_new_add_on
import dev.skillbill.designsystem.generated.resources.command_new_add_on_subtitle
import dev.skillbill.designsystem.generated.resources.command_new_horizontal_skill
import dev.skillbill.designsystem.generated.resources.command_new_horizontal_skill_subtitle
import dev.skillbill.designsystem.generated.resources.command_new_platform_pack
import dev.skillbill.designsystem.generated.resources.command_new_platform_pack_subtitle
import dev.skillbill.designsystem.generated.resources.command_open_repository
import dev.skillbill.designsystem.generated.resources.command_open_repository_subtitle
import dev.skillbill.designsystem.generated.resources.command_refresh
import dev.skillbill.designsystem.generated.resources.command_refresh_subtitle
import dev.skillbill.designsystem.generated.resources.command_save
import dev.skillbill.designsystem.generated.resources.command_save_subtitle
import org.jetbrains.compose.resources.StringResource
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
        .thenBy { it.result.id.lowercase() },
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

private fun commandCandidates(state: SkillBillState, blockedByBusy: StringResource?): List<PaletteCandidate> {
  val openRepository = PaletteCandidate(
    result = CommandPaletteResult(
      id = "command.open-repository",
      titleRes = Res.string.command_open_repository,
      subtitleRes = Res.string.command_open_repository_subtitle,
      marker = "op",
      kind = CommandPaletteResultKind.COMMAND,
      action = CommandPaletteAction.OPEN_REPOSITORY,
      disabledReasonRes = blockedByBusy,
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
        titleRes = Res.string.command_refresh,
        subtitleRes = Res.string.command_refresh_subtitle,
        marker = "rf",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.REFRESH,
        disabledReasonRes = blockedByBusy,
        acceleratorLabelRes = Res.string.accelerator_refresh,
      ),
      keywords = listOf("refresh", "reload", "repository", "tree"),
      baseRank = COMMAND_BASE_RANK,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.save",
        titleRes = Res.string.command_save,
        subtitleRes = Res.string.command_save_subtitle,
        marker = "sv",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.SAVE,
        disabledReasonRes = blockedByBusy ?: saveDisabledReason(state),
        acceleratorLabelRes = Res.string.accelerator_save,
      ),
      keywords = listOf("save", "editor", "authored", "content", "draft"),
      baseRank = COMMAND_BASE_RANK - 3,
      sortGroup = 0,
    ),
    PaletteCandidate(
      result = CommandPaletteResult(
        id = "command.install-setup",
        titleRes = Res.string.command_install_setup,
        subtitleRes = Res.string.command_install_setup_subtitle,
        marker = "in",
        kind = CommandPaletteResultKind.COMMAND,
        action = CommandPaletteAction.INSTALL_SETUP,
        disabledReasonRes = blockedByBusy ?: installSetupDisabledReason(state),
      ),
      keywords = listOf("install", "setup", "wizard", "reinstall", "agents", "packs", "telemetry", "mcp"),
      baseRank = COMMAND_BASE_RANK - 5,
      sortGroup = 0,
    ),
    newScaffoldCandidate(
      id = "command.new-horizontal-skill",
      titleRes = Res.string.command_new_horizontal_skill,
      subtitleRes = Res.string.command_new_horizontal_skill_subtitle,
      marker = "nh",
      action = CommandPaletteAction.NEW_HORIZONTAL_SKILL,
      blockedByBusy = blockedByBusy,
      state = state,
      keywords = listOf("new", "scaffold", "horizontal", "skill", "create"),
      rankOffset = -7,
    ),
    newScaffoldCandidate(
      id = "command.new-platform-pack",
      titleRes = Res.string.command_new_platform_pack,
      subtitleRes = Res.string.command_new_platform_pack_subtitle,
      marker = "np",
      action = CommandPaletteAction.NEW_PLATFORM_PACK,
      blockedByBusy = blockedByBusy,
      state = state,
      keywords = listOf("new", "scaffold", "platform", "pack", "create"),
      rankOffset = -9,
    ),
    newScaffoldCandidate(
      id = "command.new-add-on",
      titleRes = Res.string.command_new_add_on,
      subtitleRes = Res.string.command_new_add_on_subtitle,
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
  titleRes: StringResource,
  subtitleRes: StringResource,
  marker: String,
  action: CommandPaletteAction,
  blockedByBusy: StringResource?,
  state: SkillBillState,
  keywords: List<String>,
  rankOffset: Int,
): PaletteCandidate = PaletteCandidate(
  result = CommandPaletteResult(
    id = id,
    titleRes = titleRes,
    subtitleRes = subtitleRes,
    marker = marker,
    kind = CommandPaletteResultKind.COMMAND,
    action = action,
    disabledReasonRes = blockedByBusy ?: scaffoldDisabledReason(state),
  ),
  keywords = keywords,
  baseRank = COMMAND_BASE_RANK + rankOffset,
  sortGroup = 0,
)

private fun scaffoldDisabledReason(state: SkillBillState): StringResource? = when {
  state.selectedRepoPath == null -> Res.string.command_disabled_no_repo
  state.repoStatus.state != RepoLoadState.LOADED -> Res.string.command_disabled_invalid_repo
  state.scaffoldWizard != null -> Res.string.command_disabled_scaffold_wizard_open
  else -> null
}

private fun treeCandidates(state: SkillBillState, blockedByBusy: StringResource?): List<PaletteCandidate> =
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
        disabledReasonRes = blockedByBusy,
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

private fun busyDisabledReason(busyOperation: SkillBillBusyOperation?): StringResource? = when (busyOperation) {
  null -> null
  SkillBillBusyOperation.OPEN_REPO -> Res.string.command_disabled_wait_for_repo_open
  SkillBillBusyOperation.REFRESH -> Res.string.command_disabled_wait_for_refresh
  SkillBillBusyOperation.CHOOSE_DIRECTORY -> Res.string.command_disabled_wait_for_directory
  SkillBillBusyOperation.SAVE -> Res.string.command_disabled_wait_for_save
  SkillBillBusyOperation.SCAFFOLD -> Res.string.command_disabled_wait_for_scaffold
  SkillBillBusyOperation.FIRST_RUN_SETUP -> Res.string.command_disabled_wait_for_setup
  SkillBillBusyOperation.DELETE -> Res.string.command_disabled_wait_for_delete
  SkillBillBusyOperation.VALIDATE_AGENT_CONFIGS -> Res.string.command_disabled_wait_for_validate
}

private fun saveDisabledReason(state: SkillBillState): StringResource? = when {
  !state.editor.editable -> Res.string.command_disabled_not_editable
  !state.editor.dirty -> Res.string.command_disabled_not_dirty
  state.editor.saveInProgress -> Res.string.command_disabled_save_in_progress
  else -> null
}

private fun installSetupDisabledReason(state: SkillBillState): StringResource? = when {
  state.selectedRepoPath == null -> Res.string.command_disabled_no_repo
  state.repoStatus.state != RepoLoadState.LOADED -> Res.string.command_disabled_invalid_repo
  state.scaffoldWizard != null -> Res.string.command_disabled_scaffold_wizard_open
  state.firstRunSetup != null -> Res.string.command_disabled_setup_wizard_open
  else -> null
}

private fun PaletteCandidate.toRankedResult(query: String): RankedPaletteResult? {
  val score = score(query, result.id, keywords)
    ?: return null
  return RankedPaletteResult(
    result = result.copy(rank = baseRank + score),
    sortGroup = sortGroup,
  )
}

private fun score(query: String, id: String, keywords: List<String>): Int? {
  val normalizedQuery = query.trim().lowercase()
  if (normalizedQuery.isBlank()) {
    return 0
  }
  val searchable = (listOf(id) + keywords).map { it.lowercase() }
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
  TreeItemKind.AGENT_ADDON -> "aa"
  TreeItemKind.CONFIG -> "cfg"
  TreeItemKind.NATIVE_AGENT -> "ag"
  TreeItemKind.GENERATED_ARTIFACT -> "gen"
  TreeItemKind.PLACEHOLDER -> "pl"
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
