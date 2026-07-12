package skillbill.desktop.core.data.service

import skillbill.application.model.WorkflowFamilyKind
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.model.RuntimeContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class JvmWorkListGatewayTest {
  @Test
  fun `gateway renders the shared application work service without a repository session`() = runBlocking {
    val home = Files.createTempDirectory("skillbill-desktop-work-gateway")
    val component = RuntimeComponent::class.create(RuntimeContext(environment = emptyMap(), userHome = home))
    component.workflowService.open(WorkflowFamilyKind.TASK_PROSE, issueKey = "skill-117")

    val expected = component.workListService.list().work
    val actual = JvmWorkListGateway(component.workListService).list()

    assertEquals(expected.map { it.workflowId }, actual.map { it.workflowId })
    assertEquals(expected.map { it.workflowKind.wireValue }, actual.map { it.workflowKind })
    assertEquals(expected.map { it.issueKey }, actual.map { it.issueKey })
    assertEquals(expected.map { it.currentState }, actual.map { it.currentState })
    assertEquals(expected.map { it.stateEnteredAtEstimated }, actual.map { it.stateEnteredAtEstimated })
  }

  @Test
  fun `gateway resolves the timezone again on each refresh`() = runBlocking {
    val home = Files.createTempDirectory("skillbill-desktop-work-timezone")
    val component = RuntimeComponent::class.create(RuntimeContext(environment = emptyMap(), userHome = home))
    component.workflowService.open(WorkflowFamilyKind.TASK_PROSE, issueKey = "SKILL-117")
    var zoneId = ZoneId.of("UTC")
    val gateway = JvmWorkListGateway(component.workListService) { zoneId }

    val utc = gateway.list().single()
    zoneId = ZoneId.of("Pacific/Auckland")
    val auckland = gateway.list().single()

    assertNotEquals(utc.startedAt, auckland.startedAt)
    assertNotEquals(utc.stateEnteredAt, auckland.stateEnteredAt)
  }
}
