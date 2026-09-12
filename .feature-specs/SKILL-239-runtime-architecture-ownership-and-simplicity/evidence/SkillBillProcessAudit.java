import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.Unit;
import skillbill.infrastructure.fs.launcher.process.AgentRunProcessRequestFieldsKt;
import skillbill.infrastructure.fs.launcher.process.JvmAgentRunProcessRunner;
import skillbill.ports.agentrun.model.AgentRunProgressProbe;

public class SkillBillProcessAudit {
  public static void main(String[] args) throws Exception {
    Path dir = Files.createTempDirectory("skill-bill-process-audit-");
    AtomicInteger sequence = new AtomicInteger();
    var request = AgentRunProcessRequestFieldsKt.agentRunProcessRequest(
      List.of("sh", "-c", "echo $$ > child.pid; exec sleep 20"), dir,
      dsl -> {
        dsl.setProgressProbe(new AgentRunProgressProbe() {
          public String progressToken() { return Integer.toString(sequence.incrementAndGet()); }
          public String progressLabel() { return "audit-progress"; }
        });
        dsl.setOutputSink((stream, text) -> { throw new IllegalStateException("audit-output-sink-failure"); });
        return Unit.INSTANCE;
      }
    );
    try {
      new JvmAgentRunProcessRunner(Clock.systemUTC()).run(request);
      System.out.println("runner_returned_without_failure");
    } catch (IllegalStateException failure) {
      System.out.println("runner_exception=" + failure.getMessage());
      long pid = Long.parseLong(Files.readString(dir.resolve("child.pid")).trim());
      var child = ProcessHandle.of(pid).orElseThrow();
      System.out.println("child_alive_after_runner_exception=" + child.isAlive());
    } finally {
      if (Files.exists(dir.resolve("child.pid"))) {
        long pid = Long.parseLong(Files.readString(dir.resolve("child.pid")).trim());
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
      }
      Files.deleteIfExists(dir.resolve("child.pid"));
      Files.deleteIfExists(dir);
    }
  }
}
