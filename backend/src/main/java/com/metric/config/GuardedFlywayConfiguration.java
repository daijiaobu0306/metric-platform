package com.metric.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keep immutable historical SQL while allowing the explicitly repaired BOMC version and the legacy MariaDB-only V51. */
@Configuration
public class GuardedFlywayConfiguration {
    static final String REPAIRED_VERSION = "20261012190000";
    /**
     * V51 原版使用 MariaDB 方言（ADD COLUMN IF NOT EXISTS），MySQL 8 无法执行，文件已从迁移目录移除。
     * 其业务效果由 20261014143845__ensure_bomc_columns_mysql.sql 在所有 MySQL 环境等价补齐。
     * 账本里记有 51 的库（MariaDB 时代转储血脉）：51 呈 MISSING，由 *:missing 忽略并通过对账；
     * 其余库本就没有 51 记录，无文件则无 pending，永不执行。
     */
    static final String LEGACY_MARIADB_VERSION = "51";

    @Bean
    FlywayMigrationStrategy guardedMigrationStrategy() {
        return flyway -> {
            boolean repairedHistoryIgnored = false;
            for (var migration : flyway.info().all()) {
                boolean repaired = migration.getVersion() != null
                        && REPAIRED_VERSION.equals(migration.getVersion().toString());
                boolean legacy = migration.getVersion() != null
                        && LEGACY_MARIADB_VERSION.equals(migration.getVersion().toString());
                if (migration.getState() == MigrationState.IGNORED) {
                    if (!repaired && !legacy) {
                        throw new IllegalStateException("Unapplied historical migration " + migration.getVersion()
                                + "; back up and run deploy/scripts/prepare-database.py upgrade first");
                    }
                    repairedHistoryIgnored |= repaired;
                }
                if (repaired && migration.getState() == MigrationState.PENDING) {
                    throw new IllegalStateException("Historical BOMC SQL requires MySQL compatibility preparation; "
                            + "run deploy/scripts/prepare-database.py before starting");
                }
            }
            // Only the known repaired version and the legacy MariaDB-only V51 may be skipped.
            // All other ignored migrations above fail, and applied checksums are still validated.
            // *:missing covers V51 on databases that executed it before the file was retired.
            Flyway.configure().configuration(flyway.getConfiguration())
                    .ignoreMigrationPatterns("*:future", "*:ignored", "*:missing").load().migrate();
        };
    }
}
