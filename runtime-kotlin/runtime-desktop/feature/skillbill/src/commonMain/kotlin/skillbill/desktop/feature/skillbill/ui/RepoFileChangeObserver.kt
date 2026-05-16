package skillbill.desktop.feature.skillbill.ui

import kotlinx.coroutines.flow.Flow

internal expect fun observeRepoFileChanges(repoPath: String): Flow<RepoFileChangeKind>
