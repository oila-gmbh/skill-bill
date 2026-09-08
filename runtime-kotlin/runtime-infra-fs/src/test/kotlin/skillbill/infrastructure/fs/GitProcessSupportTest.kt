package skillbill.infrastructure.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitProcessSupportTest {
  @Test
  fun `commit and push wait long enough for a pre-commit hook`() {
    assertEquals(GIT_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("status", "--porcelain")))
    assertEquals(GIT_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("rev-parse", "HEAD")))
    assertEquals(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("commit", "-m", "msg")))
    assertEquals(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("commit", "--amend", "--no-edit")))
    assertEquals(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("push", "-u", "origin", "feat/x")))
    assertTrue(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS > GIT_TIMEOUT_SECONDS)
  }
}
