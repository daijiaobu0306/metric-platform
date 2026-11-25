import org.flywaydb.core.Flyway;
import java.sql.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
class DatabasePreparation {
  public static void main(String[] args) throws Exception {
    String url = System.getenv("SPRING_DATASOURCE_URL");
    String user = System.getenv("SPRING_DATASOURCE_USERNAME");
    String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
    var config = Flyway.configure().dataSource(url,user,password).locations("filesystem:"+args[1]);
    // V51 原版是 MariaDB 方言（ADD COLUMN IF NOT EXISTS），文件已从迁移目录移除；对 MariaDB 时代
    // 转储血脉的库（账本记有 51），由 *:missing 忽略对账，其余库无文件即无 pending，永不执行。
    var patterns = new java.util.ArrayList<String>(java.util.List.of("*:future", "*:ignored", "*:missing"));
    String ignored = System.getenv("SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS");
    if (ignored != null && !ignored.isBlank()) {
      for (String p : ignored.split(",")) {
        if (!p.isBlank() && !patterns.contains(p.trim())) patterns.add(p.trim());
      }
    }
    config.ignoreMigrationPatterns(patterns.toArray(new String[0]));
    if (args[0].equals("init")) {
      // This is a real baseline of an imported, verified schema, not fabricated migration successes.
      config.baselineVersion(args[2]).baselineDescription("verified schema and metric configuration baseline")
          .load().baseline();
      try (var connection = DriverManager.getConnection(url,user,password)) {
        try (var statement = connection.prepareStatement("INSERT INTO sys_user(username,password,display_name,status,is_admin,source) VALUES (?,?,?,1,1,'local')")) {
          statement.setString(1,System.getenv().getOrDefault("METRIC_ADMIN_USERNAME","admin"));
          statement.setString(2,new BCryptPasswordEncoder().encode(System.getenv("METRIC_ADMIN_PASSWORD")));
          statement.setString(3,"本地管理员");
          statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("INSERT INTO sys_user_role(user_id,role_id) SELECT u.id,r.id FROM sys_user u JOIN sys_role r ON r.role_name='管理员' WHERE u.username=?")) {
          statement.setString(1,System.getenv().getOrDefault("METRIC_ADMIN_USERNAME","admin"));
          statement.executeUpdate();
        }
      }
    }
    var flyway = config.outOfOrder(true).load();
    // Even a present-but-empty history table must never trigger the destructive V1 initializer.
    var databaseSwitch = Pattern.compile("(?im)^\\s*(?:USE\\s+|CREATE\\s+DATABASE\\b)");
    for (var migration : flyway.info().pending()) {
      if (migration.getVersion() != null && migration.getVersion().toString().equals("1")) {
        throw new IllegalStateException("Refusing historical V1 initialization; review the existing database baseline first");
      }
      Path script = Path.of(args[1]).resolve(migration.getScript());
      if (Files.isRegularFile(script) && databaseSwitch.matcher(Files.readString(script)).find()) {
        throw new IllegalStateException("Pending migration switches or creates a database: " + migration.getScript());
      }
    }
    if (args[0].equals("check")) {
      if (flyway.info().pending().length > 0) System.exit(10);
      flyway.validate();
      return;
    }
    flyway.migrate();
  }
}
