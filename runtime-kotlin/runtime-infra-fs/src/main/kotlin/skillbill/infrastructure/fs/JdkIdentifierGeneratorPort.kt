package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.process.IdentifierGeneratorPort
import java.util.UUID

@Inject
class JdkIdentifierGeneratorPort : IdentifierGeneratorPort {
  override fun randomToken(): String = UUID.randomUUID().toString()
}
