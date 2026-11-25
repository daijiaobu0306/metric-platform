package com.metric.config.service;

import com.metric.collect.service.CollectionDirectoryPreparationService;
import com.metric.config.entity.SystemConfig;
import com.metric.config.repository.SystemConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigDirectoryPreparationTest {
    @TempDir Path root;
    private final SystemConfigRepository repository = mock(SystemConfigRepository.class);
    private final CollectionDirectoryPreparationService preparation = mock(CollectionDirectoryPreparationService.class);
    private final SystemConfigBasePathProvider paths = mock(SystemConfigBasePathProvider.class);

    private ConfigService service() {
        var service = new ConfigService();
        ReflectionTestUtils.setField(service, "configRepository", repository);
        ReflectionTestUtils.setField(service, "directoryPreparation", preparation);
        ReflectionTestUtils.setField(service, "basePathProvider", paths);
        return service;
    }

    private SystemConfig config() {
        var config = new SystemConfig();
        config.setConfigKey("upload.base_path");
        config.setConfigValue(root.toString());
        return config;
    }

    @Test void failureDoesNotSaveOrInvalidateCurrentRoot() {
        when(preparation.prepare(root.toString())).thenThrow(new IllegalArgumentException("目录不可写"));
        assertThrows(IllegalArgumentException.class, () -> service().saveConfig(config()));
        verifyNoInteractions(repository, paths);
    }

    @Test void returnsReportAndInvalidatesOnlyAfterCommit() {
        var report = Map.<String, Object>of("created", List.of("in", "out"), "existing", List.of());
        when(preparation.prepare(root.toString())).thenReturn(report);
        when(repository.save(any(SystemConfig.class))).thenAnswer(i -> i.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        try {
            var saved = service().saveConfig(config());
            assertEquals(report, saved.getDirectoryPreparation());
            var order = inOrder(preparation, repository);
            order.verify(preparation).prepare(root.toString());
            order.verify(repository).save(any(SystemConfig.class));
            verifyNoInteractions(paths);
            for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
            verify(paths).invalidate();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
