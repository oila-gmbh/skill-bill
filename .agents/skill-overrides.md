# Skill Overrides

## bill-kotlin-code-check
- Treat Detekt `TooGenericExceptionCaught` findings whose message is "The caught exception is too generic." as accepted project policy: do not repair them, do not add `@Suppress`, and do not block validate or quality-check on them.
- When collecting findings for repair, drop those rule hits from the repair set and proceed with every other finding.

## bill-code-check
- When the routed checker is `bill-kotlin-code-check`, honor the `bill-kotlin-code-check` override above for Detekt `TooGenericExceptionCaught` ("The caught exception is too generic.").
