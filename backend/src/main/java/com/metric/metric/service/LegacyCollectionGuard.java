package com.metric.metric.service;

import com.metric.collect.service.CollectionDirectoryGuard;
import com.metric.metric.entity.MetricImportConfig;
import com.metric.collect.service.CollectionDirectoryPreparationService;
import com.metric.shared.util.BasePathProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Shared gate for scheduled/manual legacy scans: readiness, frequency and directory ownership. */
@Service
public class LegacyCollectionGuard {
    private static final Logger log = LoggerFactory.getLogger(LegacyCollectionGuard.class);
    private final CollectionDirectoryGuard directories;
    private final Clock clock;
    private final Map<String, Instant> lastStarted = new HashMap<>();

    @Autowired
    public LegacyCollectionGuard(CollectionDirectoryGuard directories) { this(directories, Clock.systemUTC()); }
    LegacyCollectionGuard(CollectionDirectoryGuard directories, Clock clock) { this.directories = directories; this.clock = clock; }

    @Autowired
    private BasePathProvider paths;

    /** Called only while saving a config, never by scheduled/manual scans. */
    public void prepareConfig(MetricImportConfig config) {
        validateConfig(config);
        if (!Integer.valueOf(1).equals(config.getEnabled())) return;
        CollectionDirectoryPreparationService.ensureDirectory(
                CollectionDirectoryPreparationService.resolve(paths.getBasePath(), config.getImportPath()));
        CollectionDirectoryPreparationService.ensureDirectory(
                CollectionDirectoryPreparationService.resolve(paths.getBasePath(), config.getArchivePath()));
    }

    public void validateConfig(MetricImportConfig config) {
        Integer interval = config.getScanIntervalMinutes();
        if (interval == null || interval < 1 || interval > 60) throw new IllegalArgumentException("扫描间隔须为1～60分钟");
        if (Integer.valueOf(1).equals(config.getEnabled())) {
            LegacyImportSupport.requireAvailable(config.getSubCategoryCode());
        }
        directories.checkLegacy(config.getSubCategoryCode(), config.getImportPath(), config.getArchivePath());
    }

    public synchronized void scheduled(MetricImportConfig config, Runnable action) {
        if (!LegacyImportSupport.available(config.getSubCategoryCode()) || !Integer.valueOf(1).equals(config.getEnabled())) return;
        Instant now = clock.instant();
        int interval = config.getScanIntervalMinutes() == null ? 5 : config.getScanIntervalMinutes();
        Instant previous = lastStarted.get(config.getSubCategoryCode());
        if (previous != null && now.isBefore(previous.plusSeconds(Math.max(1, interval) * 60L))) return;
        lastStarted.put(config.getSubCategoryCode(), now);
        try { manual(config, action); }
        catch (RuntimeException e) { log.warn("Legacy collection {} blocked: {}", config.getSubCategoryCode(), e.getMessage()); }
    }

    public synchronized void manual(MetricImportConfig config, Runnable action) {
        LegacyImportSupport.requireAvailable(config.getSubCategoryCode());
        if (!Integer.valueOf(1).equals(config.getEnabled())) throw new IllegalArgumentException("采集通道已停用");
        validateConfig(config);
        lastStarted.put(config.getSubCategoryCode(), clock.instant());
        action.run();
    }
}
