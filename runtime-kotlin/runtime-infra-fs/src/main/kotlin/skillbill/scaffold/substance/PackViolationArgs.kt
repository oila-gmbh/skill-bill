package skillbill.scaffold.substance

internal data class PackViolationArgs(
  val pack: String,
  val role: String,
  val files: List<String>,
  val measured: String,
  val target: String,
  val rule: String,
)

internal fun violation(metric: SpecialistMetric, metricName: String, measured: String, target: String, rule: String) =
  packViolation(
    PackViolationArgs(
      pack = metric.pack,
      role = "${metric.area}:$metricName",
      files = listOf(metric.file),
      measured = measured,
      target = target,
      rule = rule,
    ),
  )

internal fun packViolation(args: PackViolationArgs): SubstanceViolation {
  val id = (listOf(args.pack, args.role) + args.files).joinToString("|")
  return SubstanceViolation(
    id,
    args.pack,
    args.role,
    args.files.sorted(),
    args.measured,
    args.target,
    args.rule,
  )
}
