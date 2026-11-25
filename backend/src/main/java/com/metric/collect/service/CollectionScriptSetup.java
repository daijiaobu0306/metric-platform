package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Prepare the category task directories without enabling collection or changing business rules. */
@Service
public class CollectionScriptSetup {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BasePathProvider paths;
    private final CollectionDirectoryGuard guard;
    public CollectionScriptSetup(JdbcTemplate jdbc,ObjectMapper mapper,BasePathProvider paths,CollectionDirectoryGuard guard) {
        this.jdbc=jdbc;this.mapper=mapper;this.paths=paths;this.guard=guard;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void prepareDirectories() throws java.io.IOException {
        for(var row:jdbc.queryForList("SELECT config_json FROM collection_source WHERE JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.parser'))='python-worker'")) {
            var source=mapper.readValue(row.get("config_json").toString(),CollectionSource.class);
            guard.checkSource(source);
            CollectionDirectoryPreparationService.ensureDirectory(CollectionDirectoryPreparationService.resolveSource(paths.getBasePath(),source.importPath()));
            CollectionDirectoryPreparationService.ensureDirectory(CollectionDirectoryPreparationService.resolveSource(paths.getBasePath(),source.archivePath()));
        }
    }
}
