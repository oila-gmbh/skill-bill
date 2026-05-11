package skillbill.desktop.core.data.di

import me.tatarka.inject.annotations.Provides
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.service.RuntimeRepoBrowserService
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.SkillTreeService
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(UserScope::class)
interface JvmDataBindings {
  @Provides
  fun RuntimeRepoBrowserService.bindRepoSessionService(): RepoSessionService = this

  @Provides
  fun RuntimeRepoBrowserService.bindSkillTreeService(): SkillTreeService = this

  @Provides
  fun RuntimeRepoBrowserService.bindAuthoringGateway(): AuthoringGateway = this

  @Provides
  fun RuntimeRepoBrowserService.bindGitGateway(): GitGateway = this
}
