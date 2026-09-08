package skillbill.application.goalrunner.planning

object GoalPlanningSharedContextPacketLegacy {
  fun migrateFromV01(packet: Map<String, Any?>): Map<String, Any?> {
    require(packet.keys == GoalPlanningSharedContextPacket.LEGACY_V01_FIELDS) {
      "shared context packet fields are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1}'"
    }
    require(GoalPlanningSharedContextPacketValidation.isStringMap(packet["platform_packs"])) {
      "shared context platform packs are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_1}'"
    }
    val integrityPayload = packet - "integrity_sha256"
    require(packet["integrity_sha256"] == GoalPlanningSharedContextPacketValidation.digest(integrityPayload)) {
      "shared context packet integrity is invalid"
    }
    val withoutLegacy = linkedMapOf<String, Any?>()
    for (field in GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      if (field == "packet_version") {
        withoutLegacy[field] = GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2
      } else if (field != "integrity_sha256") {
        withoutLegacy[field] = packet.getValue(field)
      }
    }
    return withoutLegacy + ("integrity_sha256" to GoalPlanningSharedContextPacketValidation.digest(withoutLegacy))
  }

  fun migrateFromV02(packet: Map<String, Any?>): Map<String, Any?> {
    require(packet.keys == GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      "shared context packet fields are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2}'"
    }
    require(GoalPlanningSharedContextPacketValidation.isStringMap(packet["boundary_memory"])) {
      "shared context boundary memory is invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_2}'"
    }
    val integrityPayload = packet - "integrity_sha256"
    require(packet["integrity_sha256"] == GoalPlanningSharedContextPacketValidation.digest(integrityPayload)) {
      "shared context packet integrity is invalid"
    }
    val migrated = linkedMapOf<String, Any?>()
    for (field in GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      when (field) {
        "packet_version" -> migrated[field] = GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3
        "integrity_sha256" -> Unit
        else -> migrated[field] = packet.getValue(field)
      }
    }
    return migrated + ("integrity_sha256" to GoalPlanningSharedContextPacketValidation.digest(migrated))
  }

  fun migrateFromV03(packet: Map<String, Any?>): Map<String, Any?> {
    require(packet.keys == GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      "shared context packet fields are invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3}'"
    }
    require(GoalPlanningSharedContextPacketValidation.isStringMap(packet["boundary_memory"])) {
      "shared context boundary memory is invalid for version '${GoalPlanningSharedContextPacket.LEGACY_VERSION_0_3}'"
    }
    val integrityPayload = packet - "integrity_sha256"
    require(packet["integrity_sha256"] == GoalPlanningSharedContextPacketValidation.digest(integrityPayload)) {
      "shared context packet integrity is invalid"
    }
    val migrated = linkedMapOf<String, Any?>()
    for (field in GoalPlanningSharedContextPacket.PACKET_FIELDS) {
      when (field) {
        "packet_version" -> migrated[field] = GoalPlanningSharedContextPacket.VERSION
        "boundary_memory" -> migrated[field] = GoalPlanningSharedContextPacket.discardedCatalog()
        "integrity_sha256" -> Unit
        else -> migrated[field] = packet.getValue(field)
      }
    }
    return migrated + ("integrity_sha256" to GoalPlanningSharedContextPacketValidation.digest(migrated))
  }
}
