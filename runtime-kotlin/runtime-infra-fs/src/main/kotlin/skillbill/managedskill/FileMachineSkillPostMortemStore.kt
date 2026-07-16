package skillbill.managedskill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.managedskill.MachineSkillPostMortemSchemaValidator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class FileMachineSkillPostMortemStore(home: Path) {
  private val root = home.toAbsolutePath().normalize().resolve(".skill-bill/machine-skill-post-mortems")
  private val mapper = ObjectMapper()

  fun write(postMortem: JsonNode) {
    MachineSkillPostMortemSchemaValidator.validate(postMortem, "post-mortem")
    val id = postMortem.path("post_mortem_id").asText()
    val path = root.resolve("$id.json")
    Files.createDirectories(root)
    val temporary = Files.createTempFile(root, ".post-mortem-", ".json")
    try {
      Files.newOutputStream(temporary).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, postMortem) }
      Files.move(temporary, path, ATOMIC_MOVE)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  fun read(id: String): JsonNode {
    val path = root.resolve("$id.json")
    val node = Files.newInputStream(path).use(mapper::readTree)
    MachineSkillPostMortemSchemaValidator.validate(node, path.toString())
    return node
  }

  fun acknowledge(id: String): JsonNode {
    val updated = read(id).deepCopy<ObjectNode>().put("acknowledgement_status", "acknowledged")
    write(updated)
    return updated
  }
}
