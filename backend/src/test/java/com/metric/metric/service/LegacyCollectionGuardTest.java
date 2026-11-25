package com.metric.metric.service;

import com.metric.collect.service.CollectionDirectoryGuard;
import com.metric.metric.entity.MetricImportConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.File;
import java.nio.file.*;
import java.time.*;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyCollectionGuardTest {
    @TempDir Path root;

    @Test void savingEnabledRetiredCollectorIsRejectedWithoutCreatingDirectories() {
        var directories = mock(CollectionDirectoryGuard.class);
        var guard = new LegacyCollectionGuard(directories);
        var paths = mock(com.metric.shared.util.BasePathProvider.class);
        when(paths.getBasePath()).thenReturn(root.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "paths", paths);
        assertThrows(IllegalArgumentException.class,()->guard.prepareConfig(config("error", 5)));
        assertFalse(Files.exists(root.resolve("in")));
        assertFalse(Files.exists(root.resolve("out")));
        var disabled = config("performance", 5);
        disabled.setEnabled(0); disabled.setImportPath("disabled/in"); disabled.setArchivePath("disabled/out");
        guard.prepareConfig(disabled);
        assertFalse(Files.exists(root.resolve("disabled")));
    }

    @Test void retiredCollectorsCannotResumeWhenIntervalOrClockChanges() {
        var clock = mock(Clock.class); Instant start = Instant.parse("2026-10-13T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var guard = new LegacyCollectionGuard(mock(CollectionDirectoryGuard.class), clock);
        var oneMinute = config("error", 1); var fiveMinutes = config("performance", 5);
        Runnable one = mock(Runnable.class), five = mock(Runnable.class);
        guard.scheduled(oneMinute, one); guard.scheduled(fiveMinutes, five);
        when(clock.instant()).thenReturn(start.plusSeconds(59));
        guard.scheduled(oneMinute, one); guard.scheduled(fiveMinutes, five);
        verifyNoInteractions(one,five);
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        guard.scheduled(oneMinute, one); guard.scheduled(fiveMinutes, five);
        verifyNoInteractions(one,five);
        fiveMinutes.setScanIntervalMinutes(1); guard.scheduled(fiveMinutes, five);
        verifyNoInteractions(one,five);
    }

    @Test void unavailableDisabledAndConflictingCollectorsCannotConsumeFiles() {
        var directories = mock(CollectionDirectoryGuard.class);
        var guard = new LegacyCollectionGuard(directories);
        Runnable action = mock(Runnable.class);
        var pending = config("dataVolatility", 5);
        guard.scheduled(pending, action);
        assertThrows(IllegalArgumentException.class, () -> guard.manual(pending, action));
        var disabled = config("error", 1); disabled.setEnabled(0);
        guard.scheduled(disabled, action);
        assertThrows(IllegalArgumentException.class, () -> guard.manual(disabled, action));
        verifyNoInteractions(directories);
        var conflict = config("performance", 5);
        doThrow(new IllegalArgumentException("directory conflict")).when(directories).checkLegacy("performance", "in", "out");
        guard.scheduled(conflict, action);
        assertThrows(IllegalArgumentException.class, () -> guard.manual(conflict, action));
        verifyNoInteractions(action);
    }

    @Test void invalidFrequencyCannotBeSaved() {
        var guard = new LegacyCollectionGuard(mock(CollectionDirectoryGuard.class));
        for (Integer interval : new Integer[]{null, 0, -1, 61}) {
            var config = config("error", 1); config.setScanIntervalMinutes(interval);
            assertThrows(IllegalArgumentException.class, () -> guard.validateConfig(config));
        }
    }

    static Stream<BiFunction<File, MetricImportConfig, Integer>> placeholders() {
        return Stream.of(new ExternalCustomerImportService()::importFile, new DqImportService()::importFile,
            new DemandImportService()::importFile, new ComplaintImportService()::importFile,
            new ServiceComplaintImportService()::importFile, new SatisfactionImportService()::importFile);
    }

    @ParameterizedTest @MethodSource("placeholders")
    void placeholderImportsRejectWithoutMovingOrChangingTheFile(BiFunction<File, MetricImportConfig, Integer> importer) throws Exception {
        Path file = root.resolve("pending.csv"); Files.writeString(file, "value\n99");
        assertThrows(IllegalArgumentException.class, () -> importer.apply(file.toFile(), config("payActive", 5)));
        assertEquals("value\n99", Files.readString(file));
    }

    private MetricImportConfig config(String code, int interval) {
        var config = new MetricImportConfig(); config.setSubCategoryCode(code); config.setScanIntervalMinutes(interval);
        config.setImportPath("in"); config.setArchivePath("out"); config.setEnabled(1); return config;
    }
}
