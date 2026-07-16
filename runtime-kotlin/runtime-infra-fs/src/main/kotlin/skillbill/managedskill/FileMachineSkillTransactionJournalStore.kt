package skillbill.managedskill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import skillbill.contracts.managedskill.MachineSkillTransactionSchemaValidator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class FileMachineSkillTransactionJournalStore(home: Path) {
  private val root = home.toAbsolutePath().normalize().resolve(".skill-bill/machine-skill-transactions")
  private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

  fun read(planId: String): JsonNode? {
    val path = path(planId)
    if (!Files.exists(path)) return null
    val node = Files.newInputStream(path).use(mapper::readTree)
    MachineSkillTransactionSchemaValidator.validate(node, path.toString())
    return node
  }

  fun write(planId: String, journal: JsonNode) {
    MachineSkillTransactionSchemaValidator.validate(journal, planId)
    val path = path(planId)
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent, ".journal-", ".json")
    try {
      Files.newOutputStream(temporary).use { mapper.writeValue(it, journal) }
      Files.move(temporary, path, ATOMIC_MOVE)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  fun incomplete(): List<JsonNode> = if (!Files.isDirectory(root)) {
    emptyList()
  } else {
    Files.newDirectoryStream(root).use { directories ->
      directories
        .mapNotNull { read(it.fileName.toString()) }
        .filter { it.path("status").asText() !in setOf("committed", "recovered") }
    }
  }

  private fun path(planId: String): Path {
    require(planId.matches(Regex("[a-f0-9]{64}")))
    return root.resolve(planId).resolve("journal.json")
  }
}
