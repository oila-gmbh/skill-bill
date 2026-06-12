package skillbill.desktop.feature.skillbill.state

import skillbill.desktop.core.domain.model.SkillBillTreeItem

internal fun normalizeRepoPath(repoPath: String): String = repoPath.trim().trimEnd('/').ifEmpty { "/" }

internal fun List<SkillBillTreeItem>.flatten(): List<SkillBillTreeItem> =
  flatMap { item -> listOf(item) + item.children.flatten() }

internal fun List<SkillBillTreeItem>.visibleItems(expandedNodeIds: Set<String>): List<SkillBillTreeItem> =
  flatMap { item ->
    if (item.id in expandedNodeIds) {
      listOf(item) + item.children.visibleItems(expandedNodeIds)
    } else {
      listOf(item)
    }
  }

internal fun SkillBillTreeItem.snapshot(): SkillBillTreeItem = copy(
  children = children.map(SkillBillTreeItem::snapshot),
)

internal fun reconcileExpandedNodeIds(
  previousExpandedNodeIds: Set<String>,
  treeItems: List<SkillBillTreeItem>,
  preserveExpansion: Boolean,
): Set<String> {
  val expandableIds = treeItems.flatten().filter { it.children.isNotEmpty() }.map(SkillBillTreeItem::id).toSet()
  return if (preserveExpansion) {
    previousExpandedNodeIds.intersect(expandableIds)
  } else {
    emptySet()
  }
}
