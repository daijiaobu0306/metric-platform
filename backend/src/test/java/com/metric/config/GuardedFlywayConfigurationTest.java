package com.metric.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardedFlywayConfigurationTest {
    private static final String[] PATTERNS = {"*:future", "*:ignored", "*:missing"};

    private Flyway flyway(MigrationInfo... migrations) {
        var flyway = mock(Flyway.class);
        var info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.all()).thenReturn(migrations);
        when(flyway.getConfiguration()).thenReturn(mock(Configuration.class));
        return flyway;
    }

    private MigrationInfo migration(String version, MigrationState state) {
        var info = mock(MigrationInfo.class);
        when(info.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.getState()).thenReturn(state);
        return info;
    }

    @Test void normalAndBaselineDatabasesMigrateNormally() {
        var flyway = flyway(migration("20261014143845", MigrationState.BASELINE));
        var fluent = mock(FluentConfiguration.class);
        var compatible = mock(Flyway.class);
        try (var factory = mockStatic(Flyway.class)) {
            factory.when(Flyway::configure).thenReturn(fluent);
            when(fluent.configuration(any(Configuration.class))).thenReturn(fluent);
            when(fluent.ignoreMigrationPatterns(PATTERNS)).thenReturn(fluent);
            when(fluent.load()).thenReturn(compatible);
            new GuardedFlywayConfiguration().guardedMigrationStrategy().migrate(flyway);
            verify(compatible).migrate();
            verify(flyway, never()).migrate();
        }
    }

    @Test void unknownHistoricalMigrationsCannotBeSilentlyIgnored() {
        var flyway = flyway(migration("20260901000000", MigrationState.IGNORED));
        assertThrows(IllegalStateException.class, () ->
                new GuardedFlywayConfiguration().guardedMigrationStrategy().migrate(flyway));
        verify(flyway, never()).migrate();
    }

    @Test void pendingIncompatibleScriptRequiresPreparation() {
        var flyway = flyway(migration("20261012190000", MigrationState.PENDING));
        assertThrows(IllegalStateException.class, () ->
                new GuardedFlywayConfiguration().guardedMigrationStrategy().migrate(flyway));
        verify(flyway, never()).migrate();
    }

    @Test void legacyMariaDBV51AsMissingOrIgnoredIsTolerated() {
        for (MigrationState state : new MigrationState[]{MigrationState.MISSING_SUCCESS, MigrationState.IGNORED}) {
            var flyway = flyway(migration("51", state));
            var fluent = mock(FluentConfiguration.class);
            var compatible = mock(Flyway.class);
            try (var factory = mockStatic(Flyway.class)) {
                factory.when(Flyway::configure).thenReturn(fluent);
                when(fluent.configuration(any(Configuration.class))).thenReturn(fluent);
                when(fluent.ignoreMigrationPatterns(PATTERNS)).thenReturn(fluent);
                when(fluent.load()).thenReturn(compatible);
                new GuardedFlywayConfiguration().guardedMigrationStrategy().migrate(flyway);
                verify(compatible).migrate();
                verify(flyway, never()).migrate();
            }
        }
    }

    @Test void onlyKnownIgnoredVersionUsesTheCompatibilityValidation() {
        var flyway = flyway(migration("20261012190000", MigrationState.IGNORED));
        var fluent = mock(FluentConfiguration.class);
        var compatible = mock(Flyway.class);
        try (var factory = mockStatic(Flyway.class)) {
            factory.when(Flyway::configure).thenReturn(fluent);
            when(fluent.configuration(any(Configuration.class))).thenReturn(fluent);
            when(fluent.ignoreMigrationPatterns(PATTERNS)).thenReturn(fluent);
            when(fluent.load()).thenReturn(compatible);
            new GuardedFlywayConfiguration().guardedMigrationStrategy().migrate(flyway);
            verify(compatible).migrate();
            verify(flyway, never()).migrate();
        }
    }
}
