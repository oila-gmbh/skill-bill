import java.util.List;
public class SkillBillWireAudit {
  public static void main(String[] args) throws Exception {
    Class<?> sourceType = Class.forName("skillbill.architecture.SourceFile");
    Object source = sourceType.getConstructor(String.class, String.class, List.class, String.class).newInstance(
      "fixture/UngovernedPayload.kt", "fixture", List.of(),
      "package fixture\nfun read(payload: Map<String, Any?>) = payload[\"unowned_contract_key\"]\n"
    );
    Class<?> scannerType = Class.forName("skillbill.architecture.WireVocabularyArchitectureSupport");
    Object result = scannerType.getMethod("scanSourceFiles", List.class, boolean.class).invoke(
      scannerType.getField("INSTANCE").get(null), List.of(source), true
    );
    Object violations = result.getClass().getMethod("getViolations").invoke(result);
    System.out.println("unowned_payload_key_violations=" + violations);
  }
}
