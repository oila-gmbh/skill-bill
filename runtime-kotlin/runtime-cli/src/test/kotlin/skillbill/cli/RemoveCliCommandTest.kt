package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.JsonSupport
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-46 AC7 / AC9: end-to-end CLI tests for `skill-bill remove`.
 *
 * Covers:
 * - `--dry-run` returns a preview payload with exit 0 and DOES NOT delete files on disk.
 * - Refusal of `.bill-shared` returns exit code 1 with `status="error"`.
 * - Refusal of `kotlin` without `--allow-shipped` returns exit code 1.
 * - `kotlin` succeeds when `--allow-shipped` is passed.
 */
class RemoveCliCommandTest {
  @Test
  fun `remove without target returns examples instead of generic missing argument error`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-missing-target")
    val result = CliRuntime.run(
      listOf("remove", "--format", "json"),
      CliRuntimeContext(userHome = tempDir),
    )
    val payload = decodeJsonObject(result.stdout)
    val error = payload["error"].toString()

    assertEquals(1, result.exitCode)
    assertEquals("error", payload["status"].toString().trim('"'))
    assertTrue("skill-bill remove skill:bill-my-skill --dry-run" in error)
    assertTrue("skill-bill remove platform:my-platform --dry-run" in error)
    assertTrue("skill-bill remove addon:platform-packs/kmp/addons/my-addon.md --dry-run" in error)
  }

  @Test
  fun `remove --dry-run returns a preview payload without mutating the repo`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-dry-run")
    val skillDir = tempDir.resolve("skills/bill-foo")
    Files.createDirectories(skillDir)
    val contentFile = skillDir.resolve("content.md")
    val manifestFile = skillDir.resolve("manifest.yaml")
    val contentBytes = "stub".toByteArray()
    val manifestBytes = "name: bill-foo\nkind: horizontal\n".toByteArray()
    Files.write(contentFile, contentBytes)
    Files.write(manifestFile, manifestBytes)

    val context = CliRuntimeContext(userHome = tempDir)
    val result = CliRuntime.run(
      // SKILL-49: `bill-*` skills are the product surface; the maintainer CLI still allows
      // removal via `--allow-shipped` (same shape as `kotlin` / `kmp`).
      listOf(
        "remove",
        "skill:bill-foo",
        "--repo-root",
        tempDir.toString(),
        "--allow-shipped",
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("preview", payload["status"].toString().trim('"'))
    // F-006-TESTING: the directory must still exist AND both files survive byte-identical so the
    // assertion proves dry-run truly did not touch the repo. Previously the test only checked the
    // directory's existence, which would also pass if dry-run silently truncated the content file.
    assertTrue(Files.exists(skillDir))
    assertTrue(Files.exists(contentFile))
    assertEquals("stub", Files.readString(contentFile))
    assertTrue(Files.exists(manifestFile))
    assertTrue(Files.readAllBytes(manifestFile).contentEquals(manifestBytes))
  }

  @Test
  fun `remove dry-run uses top-level home override for agent symlink preview`() {
    val repoRoot = Files.createTempDirectory("skillbill-cli-remove-home-repo")
    val contextHome = Files.createTempDirectory("skillbill-cli-remove-context-home")
    val selectedHome = Files.createTempDirectory("skillbill-cli-remove-selected-home")
    val skillDir = repoRoot.resolve("skills/bill-foo")
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), "# bill-foo\n")
    val context = CliRuntimeContext(userHome = contextHome)

    val result = CliRuntime.run(
      listOf(
        "--home",
        selectedHome.toString(),
        "remove",
        "skill:bill-foo",
        "--repo-root",
        repoRoot.toString(),
        "--allow-shipped",
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    val symlinkPaths = (payload["agent_symlink_unlinks"] as? List<*>)
      .orEmpty()
      .mapNotNull { entry -> (entry as? Map<*, *>)?.get("path")?.toString() }

    assertTrue(symlinkPaths.isNotEmpty(), "dry-run should preview native-agent symlink paths")
    assertTrue(
      symlinkPaths.all { path -> selectedHome.toString() in path },
      "all symlink preview paths should use the selected --home: $symlinkPaths",
    )
    assertTrue(
      symlinkPaths.none { path -> contextHome.toString() in path },
      "symlink preview paths must not use the process/context home: $symlinkPaths",
    )
  }

  @Test
  fun `remove refuses dot-bill-shared with status error`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-shared")
    val context = CliRuntimeContext(userHome = tempDir)
    val result = CliRuntime.run(
      listOf(
        "remove",
        "skill:.bill-shared",
        "--repo-root",
        tempDir.toString(),
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
    assertEquals(1, result.exitCode)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("error", payload["status"].toString().trim('"'))
  }

  @Test
  fun `remove refuses bill-prefixed horizontal product skill without --allow-shipped`() {
    // SKILL-49: `bill-*` horizontal skills are product surfaces. The desktop UI hides the
    // Delete affordance via `isBuiltInName`; this test pins the matching CLI refusal so the
    // domain `enforceRefusalPolicy` predicate is the load-bearing rule on every surface.
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-bill")
    val context = CliRuntimeContext(userHome = tempDir)
    val result = CliRuntime.run(
      listOf(
        "remove",
        "skill:bill-code-review",
        "--repo-root",
        tempDir.toString(),
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
    assertEquals(1, result.exitCode)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("error", payload["status"].toString().trim('"'))
    val error = payload["error"].toString()
    assertTrue("--allow-shipped" in error)
    assertTrue("allowShipped" !in error)
    assertTrue("Why this is protected:" in error)
    assertTrue("bill-* skills and kotlin/kmp pre-shells are shipped product surfaces" in error)
    assertTrue("skill-bill remove skill:bill-code-review --dry-run --allow-shipped" in error)
    assertTrue("skill-bill remove skill:bill-code-review --allow-shipped" in error)
  }

  @Test
  fun `remove refuses kotlin without --allow-shipped`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-kotlin")
    val context = CliRuntimeContext(userHome = tempDir)
    val result = CliRuntime.run(
      listOf("remove", "skill:kotlin", "--repo-root", tempDir.toString(), "--dry-run", "--format", "json"),
      context,
    )
    assertEquals(1, result.exitCode)
  }

  @Test
  fun `remove platform kotlin succeeds without --allow-shipped`() {
    // SKILL-49: platform packs are the user-extension surface; shipped first-party packs
    // (`kotlin`, `kmp`) are user-removable from the CLI without `--allow-shipped`. The `skill:`
    // axis remains gated (the test below pins that for kotlin).
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-platform-kotlin")
    Files.createDirectories(tempDir.resolve("platform-packs/kotlin"))
    Files.createDirectories(tempDir.resolve("skills/kotlin"))
    val context = CliRuntimeContext(userHome = tempDir)
    val result = CliRuntime.run(
      listOf(
        "remove",
        "platform:kotlin",
        "--repo-root",
        tempDir.toString(),
        "--dry-run",
        "--format",
        "json",
      ),
      context,
    )
    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("preview", payload["status"].toString().trim('"'))
  }

  @Test
  fun `remove platform deletes the platform pack folder`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-platform-delete")
    val platformPackRoot = tempDir.resolve("platform-packs/example")
    val pairedPreShellRoot = tempDir.resolve("skills/example")
    Files.createDirectories(platformPackRoot.resolve("code-review/bill-example-code-review"))
    Files.createDirectories(pairedPreShellRoot)
    Files.writeString(platformPackRoot.resolve("code-review/bill-example-code-review/content.md"), "# example\n")
    Files.writeString(pairedPreShellRoot.resolve("content.md"), "# example\n")
    val context = CliRuntimeContext(userHome = tempDir)

    val result = CliRuntime.run(
      listOf(
        "remove",
        "platform:example",
        "--repo-root",
        tempDir.toString(),
        "--format",
        "json",
      ),
      context,
    )

    assertEquals(0, result.exitCode, result.stdout)
    val payload = decodeJsonObject(result.stdout)
    assertEquals("ok", payload["status"].toString().trim('"'))
    assertTrue(!Files.exists(platformPackRoot, LinkOption.NOFOLLOW_LINKS), "platform pack folder should be deleted")
    assertTrue(
      !Files.exists(pairedPreShellRoot, LinkOption.NOFOLLOW_LINKS),
      "paired pre-shell folder should be deleted",
    )
  }

  @Test
  fun `remove kotlin succeeds when --allow-shipped is passed in dry-run`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-remove-kotlin-allow")
    Files.createDirectories(tempDir.resolve("skills/kotlin"))
    val context = CliRuntimeContext(userHome = tempDir)
    val result = CliRuntime.run(
      listOf(
        "remove",
        "skill:kotlin",
        "--repo-root",
        tempDir.toString(),
        "--dry-run",
        "--allow-shipped",
        "--format",
        "json",
      ),
      context,
    )
    assertEquals(0, result.exitCode, result.stdout)
  }

  private fun decodeJsonObject(raw: String): Map<String, Any?> {
    val parsed = JsonSupport.parseObjectOrNull(raw) ?: error("invalid JSON: $raw")
    @Suppress("UNCHECKED_CAST")
    return (JsonSupport.jsonElementToValue(parsed) as? Map<String, Any?>) ?: error("not an object: $raw")
  }
}
