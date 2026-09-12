import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import skillbill.infrastructure.sqlite.core.DatabaseRuntime;

public class SkillBillDatabaseAudit {
  static final List<String> statements = new ArrayList<>();
  static Object wrap(Object target, Class<?> api, String preparedSql) {
    return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, (proxy, method, args) -> {
      try {
        String name = method.getName();
        if (name.startsWith("execute")) statements.add(args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : preparedSql);
        Object value = method.invoke(target, args);
        if (value instanceof PreparedStatement) return wrap(value, PreparedStatement.class, (String) args[0]);
        if (value instanceof Statement) return wrap(value, Statement.class, null);
        return value;
      } catch (InvocationTargetException e) { throw e.getCause(); }
    });
  }
  public static void main(String[] args) throws Exception {
    DriverManager.registerDriver(new Driver() {
      public Connection connect(String url, Properties properties) throws SQLException {
        if (!acceptsURL(url)) return null;
        return (Connection) wrap(new org.sqlite.JDBC().connect(url, properties), Connection.class, null);
      }
      public boolean acceptsURL(String url) { return url.startsWith("jdbc:sqlite:"); }
      public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
      public int getMajorVersion() { return 1; }
      public int getMinorVersion() { return 0; }
      public boolean jdbcCompliant() { return false; }
      public Logger getParentLogger() { return Logger.getGlobal(); }
    });
    var dir = Files.createTempDirectory("skill-bill-database-audit-");
    var db = dir.resolve("audit.db");
    try {
      DatabaseRuntime.INSTANCE.ensureDatabase(db).close();
      statements.clear();
      DatabaseRuntime.INSTANCE.ensureDatabase(db).close();
      System.out.println("second_open_executed_statements=" + statements.size());
      System.out.println("second_open_update_statements=" + statements.stream().filter(Objects::nonNull).filter(s -> s.stripLeading().startsWith("UPDATE")).count());
      System.out.println("second_open_begin_immediate=" + statements.stream().filter(Objects::nonNull).filter(s -> s.contains("BEGIN IMMEDIATE")).count());
      for (String sql : statements) if (sql != null && sql.stripLeading().startsWith("UPDATE")) System.out.println(sql.replaceAll("\\s+", " ").substring(0, Math.min(180, sql.replaceAll("\\s+", " ").length())));
    } finally {
      try (var paths = Files.walk(dir)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
  }
}
