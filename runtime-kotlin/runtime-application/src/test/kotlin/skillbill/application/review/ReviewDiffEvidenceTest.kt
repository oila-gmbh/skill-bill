package skillbill.application.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewDiffEvidenceTest {
  @Test
  fun `routing evidence contains changed lines but not unchanged hunk context`() {
    val evidence = ReviewDiffEvidence.parse(
      """
      diff --git a/src/Main.kt b/src/Main.kt
      --- a/src/Main.kt
      +++ b/src/Main.kt
      @@ -1,2 +1,2 @@
       @Composable fun unchangedContext() = Unit
      -val before = 1
      +val after = 2
      """.trimIndent(),
    )

    assertEquals(listOf("src/Main.kt"), evidence.files.map { it.path })
    assertTrue(evidence.files.single().changedContent.contains("val after"))
    assertFalse(evidence.files.single().changedContent.contains("unchangedContext"))
    assertTrue(evidence.hunks.single().content.contains("unchangedContext"))
  }

  @Test
  fun `authoritative evidence retains deletion binary mode rename and quoted records`() {
    val evidence = ReviewDiffEvidence.parse(
      """
      diff --git a/deleted.kt b/deleted.kt
      deleted file mode 100644
      --- a/deleted.kt
      +++ /dev/null
      @@ -1 +0,0 @@
      -val deleted = true
      diff --git a/image.bin b/image.bin
      Binary files a/image.bin and b/image.bin differ
      diff --git a/script.sh b/script.sh
      old mode 100644
      new mode 100755
      diff --git a/old.kt b/new.kt
      similarity index 100%
      rename from old.kt
      rename to new.kt
      diff --git "a/path with space.kt" "b/path with space.kt"
      --- "a/path with space.kt"
      +++ "b/path with space.kt"
      @@ -1 +1 @@
      -val old = 1
      +val new = 2
      """.trimIndent(),
    )

    assertEquals(
      listOf("deleted.kt", "image.bin", "script.sh", "new.kt", "path with space.kt"),
      evidence.files.map { it.path },
    )
    assertEquals(5, evidence.hunks.size)
    assertTrue(evidence.files.first().changedContent.contains("deleted"))
  }

  @Test
  fun `headers with spaces and quoted utf8 bytes retain exact old new and authoritative paths`() {
    val evidence = ReviewDiffEvidence.parse(
      """
      diff --git a/mode only file.kt b/mode only file.kt
      old mode 100644
      new mode 100755
      diff --git "a/na\303\257ve.kt" "b/renamed \303\251.kt"
      similarity index 100%
      rename from "na\303\257ve.kt"
      rename to "renamed \303\251.kt"
      """.trimIndent(),
    )

    assertEquals(listOf("mode only file.kt", "renamed é.kt"), evidence.files.map { it.path })
    assertEquals("mode only file.kt", evidence.files[0].oldPath)
    assertEquals("mode only file.kt", evidence.files[0].newPath)
    assertEquals("naïve.kt", evidence.files[1].oldPath)
    assertEquals("renamed é.kt", evidence.files[1].newPath)
  }

  @Test
  fun `path sources preserve one prefix repository segments copies and literal backslashes`() {
    val evidence = ReviewDiffEvidence.parse(
      """
      diff --git a/b/old.kt b/b/old.kt
      --- a/b/old.kt
      +++ /dev/null
      diff --git a/a/from.kt b/b/to.kt
      similarity index 100%
      rename from a/from.kt
      rename to b/to.kt
      diff --git a/a/source.kt b/b/copy.kt
      similarity index 100%
      copy from a/source.kt
      copy to b/copy.kt
      diff --git "a/path\\name.kt" "b/path\\name.kt"
      --- "a/path\\name.kt"
      +++ "b/path\\name.kt"
      """.trimIndent(),
    )

    assertEquals(listOf("b/old.kt", "b/to.kt", "b/copy.kt", "path\\name.kt"), evidence.files.map { it.path })
    assertEquals("a/from.kt", evidence.files[1].oldPath)
    assertEquals("a/source.kt", evidence.files[2].oldPath)
  }

  @Test
  fun `unquoted space headers use record corroboration and reject unresolved ambiguity`() {
    val corroborated = ReviewDiffEvidence.parse(
      """
      diff --git a/old name.kt b/new b/name.kt
      similarity index 100%
      rename from old name.kt
      rename to new b/name.kt
      """.trimIndent(),
    )

    assertEquals("old name.kt", corroborated.files.single().oldPath)
    assertEquals("new b/name.kt", corroborated.files.single().newPath)
    assertFailsWith<IllegalArgumentException> {
      ReviewDiffEvidence.parse("diff --git a/old name.kt b/new b/name.kt\nold mode 100644\nnew mode 100755")
    }
  }

  @Test
  fun `disagreeing and traversal path sources fail before evidence construction`() {
    assertFailsWith<IllegalArgumentException> {
      ReviewDiffEvidence.parse(
        """
        diff --git a/old.kt b/new.kt
        --- a/different.kt
        +++ b/new.kt
        """.trimIndent(),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      ReviewDiffEvidence.parse("diff --git a/../secret.kt b/../secret.kt")
    }
  }
}
