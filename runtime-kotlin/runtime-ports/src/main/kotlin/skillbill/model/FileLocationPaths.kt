package skillbill.model

import java.nio.file.Path
import java.nio.file.Paths

fun FileLocation.toPath(): Path = Paths.get(value)
