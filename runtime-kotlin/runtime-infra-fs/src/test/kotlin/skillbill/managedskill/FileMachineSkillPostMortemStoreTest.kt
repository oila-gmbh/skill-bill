package skillbill.managedskill

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class FileMachineSkillPostMortemStoreTest {
  @Test
  fun `recovery exposes persistent post mortems until acknowledgement`() {
    val home = Files.createTempDirectory("machine-skill-post-mortem")
    val store = FileMachineSkillPostMortemStore(home)
    val node = ObjectMapper().createObjectNode().apply {
      put("contract_version", "0.1")
      put("post_mortem_id", "rollback-1")
      put("plan_id", "a".repeat(64))
      put("created_at", "2026-07-16T12:00:00Z")
      put("acknowledgement_status", "unacknowledged")
      putArray("affected_paths").add(home.resolve("target").toString())
      putArray("attempted_operations").add("RETARGET:AGENT_LINK")
      putArray("rollback_evidence").add("restore link: access denied")
      putArray("recovery_actions").add("Inspect the target before retrying")
    }

    store.write(node)
    assertEquals(listOf("rollback-1"), store.unacknowledgedIds())
    store.acknowledge("rollback-1")
    assertEquals(emptyList(), store.unacknowledgedIds())
  }

  @Test
  fun `transaction recovery reports persisted incomplete rollback`() {
    val home = Files.createTempDirectory("machine-skill-recovery")
    val store = FileMachineSkillPostMortemStore(home)
    val node = ObjectMapper().createObjectNode().apply {
      put("contract_version", "0.1")
      put("post_mortem_id", "rollback-2")
      put("plan_id", "b".repeat(64))
      put("created_at", "2026-07-16T12:00:00Z")
      put("acknowledgement_status", "unacknowledged")
      putArray("affected_paths").add(home.resolve("target").toString())
      putArray("attempted_operations").add("REPLACE:RECORD")
      putArray("rollback_evidence").add("restore record: access denied")
      putArray("recovery_actions").add("Restore the record from backup")
    }
    store.write(node)

    assertEquals(
      listOf("rollback-2"),
      FileSystemMachineSkillTransaction(home, emptyList()).recoverIncompleteTransactions(),
    )
  }
}
