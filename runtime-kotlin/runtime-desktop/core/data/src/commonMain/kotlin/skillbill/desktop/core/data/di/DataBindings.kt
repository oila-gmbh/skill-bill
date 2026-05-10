package skillbill.desktop.core.data.di

import me.tatarka.inject.annotations.Provides
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.service.PlaceholderAuthoringGateway
import skillbill.desktop.core.data.service.PlaceholderGitGateway
import skillbill.desktop.core.data.service.PlaceholderRepoSessionService
import skillbill.desktop.core.data.service.PlaceholderSkillTreeService
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(UserScope::class)
interface DataBindings {
  @Provides
  fun PlaceholderRepoSessionService.bindRepoSessionService(): RepoSessionService = this

  @Provides
  fun PlaceholderSkillTreeService.bindSkillTreeService(): SkillTreeService = this

  @Provides
  fun PlaceholderAuthoringGateway.bindAuthoringGateway(): AuthoringGateway = this

  @Provides
  fun PlaceholderGitGateway.bindGitGateway(): GitGateway = this
}
