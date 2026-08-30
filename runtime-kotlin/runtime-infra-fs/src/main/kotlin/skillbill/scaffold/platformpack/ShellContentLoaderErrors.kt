package skillbill.scaffold.platformpack

import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidFallbackCapabilityError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidValidationGateDeclarationError
import skillbill.error.MissingContentFileError
import skillbill.error.MissingManifestError
import skillbill.error.MissingRequiredSectionError
import java.nio.file.Path

internal fun invalidManifestSchema(message: String): Nothing {
  throw InvalidManifestSchemaError(message)
}

internal fun missingManifestContent(message: String): Nothing {
  throw MissingContentFileError(message)
}

internal fun missingManifestSection(message: String): Nothing {
  throw MissingRequiredSectionError(message)
}

internal fun missingPlatformManifest(slug: String, manifestPath: Path): Nothing {
  throw MissingManifestError("Platform pack '$slug': expected manifest at '$manifestPath' but it is missing.")
}

internal fun invalidFallbackCapability(message: String): Nothing {
  throw InvalidFallbackCapabilityError(message)
}

internal fun contractVersionMismatch(message: String): Nothing {
  throw ContractVersionMismatchError(message)
}

internal fun invalidValidationGateDeclaration(message: String): Nothing {
  throw InvalidValidationGateDeclarationError(message)
}

internal fun invalidManifestSchemaFromPath(message: String, cause: Throwable): Nothing {
  throw InvalidManifestSchemaError(message, cause)
}
