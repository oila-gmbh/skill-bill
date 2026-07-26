package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.review.context.model.ReviewPacketConsumerContract

@Inject
class ClasspathReviewSpecialistContractProvider : ReviewSpecialistContractProvider {
  override fun authoritativeContract(): String {
    val source = requireNotNull(
      javaClass.classLoader.getResourceAsStream(ReviewPacketConsumerContract.RESOURCE_PATH),
    ) {
      "Packaged authoritative delegated-review specialist contract is missing at " +
        "${ReviewPacketConsumerContract.RESOURCE_PATH}."
    }.use { it.readBytes().toString(Charsets.UTF_8) }
    return ReviewPacketConsumerContract.authoritativeSpecialistContract(source)
  }
}
