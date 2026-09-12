# Validation evidence

## Existing architecture tests

From runtime-kotlin:

```sh
./gradlew :runtime-core:test \
  --tests 'skillbill.architecture.RuntimeGradleModuleLayeringTest' \
  --tests 'skillbill.architecture.RuntimeLayerBoundaryArchitectureTest' \
  --tests 'skillbill.architecture.RuntimeEngineInboundApiTest' \
  --tests 'skillbill.architecture.RuntimeImplementationImportRulesTest' \
  --tests 'skillbill.architecture.RuntimeArchitectureDocumentationTest' \
  --tests 'skillbill.architecture.ProductionLogicalTypeLineCeilingArchitectureTest' \
  --tests 'skillbill.architecture.WireVocabularyArchitectureTest' \
  --console=plain
```

Result: BUILD SUCCESSFUL in 20s. Seven classes, 36 tests, zero failures, zero errors. The Gradle log is preserved as architecture-tests.log. The log includes prerequisites compiled by the selected test task; it is not a full quality-gate run.

## Isolated probes

The three Java files in this directory were compiled against the existing runtime module build classes and their cached dependencies with JDK 21. They call the actual compiled runtime or scanner. The classpath used by this audit contained each declared module's build/classes/kotlin/main and build/resources/main, Kotlin stdlib, SQLite JDBC, SLF4J, serialization and SnakeYAML. The wire probe also used runtime-core/build/classes/kotlin/test.

To repeat, compile the runtime and resolve the corresponding module runtime classpath through Gradle. Set AUDIT_CP to that classpath; include runtime-core test classes for the wire probe. Compile these files to a temporary directory and run each class from that directory. Do not use a live runtime database. The process probe creates and explicitly kills only its own child. The database probe creates and removes its own database directory.

```sh
javac -cp "$AUDIT_CP" -d "$AUDIT_CLASSES" SkillBillProcessAudit.java SkillBillDatabaseAudit.java SkillBillWireAudit.java
java -cp "$AUDIT_CLASSES:$AUDIT_CP" SkillBillProcessAudit
java -cp "$AUDIT_CLASSES:$AUDIT_CP" SkillBillDatabaseAudit
java -cp "$AUDIT_CLASSES:$AUDIT_CP" SkillBillWireAudit
```

Process result: the injected output-sink exception escaped and the child was still alive. The probe cleaned it up.

Database result: the second open executed 250 SQL statements, ten updates and two BEGIN IMMEDIATE statements. This counts JDBC execute calls, not affected rows or latency.

Wire result: the scanner returned an empty violation list for an undeclared literal payload key with payload checks enabled.

## Spec validation

Before publication, the complete parent/subtask/manifest bundle was assembled in memory. Draft 2020-12 validation used orchestration/contracts/decomposition-manifest-schema.yaml. Additional checks covered execution-model coherence, subtask uniqueness, earlier-only dependencies, required spec sections, and acceptance-list extraction. Each target file was staged before publishing the complete directory atomically. All runtime state remains pending and current_subtask_intent is none. After publication, the compiled Kotlin DecompositionManifestSchemaValidator also accepted the manifest, including its coherence rules. The result is preserved in runtime-spec-validation.txt.

## Limits

The workspace was concurrently edited. The inventory records a later HEAD and source hashes, not a promise that the selected test build represents every later edit. No full suite, installation, external mutation, or production performance measurement ran. Probe outputs are local observations of these fixtures.
