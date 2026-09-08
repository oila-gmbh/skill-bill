package skillbill.ports.idestatus

import skillbill.boundary.OpenBoundaryMap

interface IdeStatusValidator {
  @OpenBoundaryMap("IDE status snapshot wire map at the schema-validation seam")
  fun validate(snapshot: Map<String, Any?>, sourceLabel: String)
}
