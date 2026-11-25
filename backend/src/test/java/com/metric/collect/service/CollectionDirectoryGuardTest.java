package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionDirectoryGuardTest {
    @TempDir Path root;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void rejectsAllCrossDirectionsWithLegacyAndAllowsIndependentPaths() {
        var guard = guard();
        when(jdbc.queryForList("SELECT sub_category_code,import_path,archive_path FROM metric_import_config WHERE enabled=1"))
            .thenReturn(List.of(Map.of("sub_category_code", "error", "import_path", "legacy/in", "archive_path", "legacy/out")));
        for (String legacy : List.of("legacy/in", "legacy/out", "legacy/in/sub", "legacy")) {
            assertThrows(IllegalArgumentException.class, () -> guard.checkSource(source(legacy, "new/out")));
            assertThrows(IllegalArgumentException.class, () -> guard.checkSource(source("new/in", legacy)));
        }
        assertThrows(IllegalArgumentException.class, () -> guard.checkSource(source("new/in", root.resolve("legacy/in").toString())));
        assertDoesNotThrow(() -> guard.checkSource(source("new/in", "new/out")));
    }

    @Test void legacyEditsAndExistingScansCannotClaimNewTaskDirectories() throws Exception {
        var guard = guard();
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source"))
            .thenReturn(List.of(Map.of("code", "new", "config_json", mapper.writeValueAsString(source("new/in", "new/out")))));
        assertThrows(IllegalArgumentException.class, () -> guard.checkLegacy("error", "old/in", "new/in"));
        assertThrows(IllegalArgumentException.class, () -> guard.checkLegacy("error", "new/out", "old/out"));
        assertDoesNotThrow(() -> guard.checkSource(source("new/in", "new/out")));
        assertDoesNotThrow(() -> guard.checkLegacy("error", "old/in", "old/out"));
    }

    @Test void twoLegacyConfigsCannotShareAnInbox() {
        var guard = guard();
        when(jdbc.queryForList("SELECT sub_category_code,import_path,archive_path FROM metric_import_config WHERE enabled=1"))
            .thenReturn(List.of(Map.of("sub_category_code", "error", "import_path", "shared", "archive_path", "old/out")));
        assertThrows(IllegalArgumentException.class, () -> guard.checkLegacy("performance", "shared", "other/out"));
        assertDoesNotThrow(() -> guard.checkLegacy("error", "shared", "old/out"));
    }

    private CollectionDirectoryGuard guard() {
        var paths = mock(BasePathProvider.class); when(paths.getBasePath()).thenReturn(root.toString());
        return new CollectionDirectoryGuard(jdbc, mapper, paths);
    }

    private CollectionSource source(String in, String out) {
        return new CollectionSource("new", "new", in, out, 60, 5, "mapped-file", "csv", "*.csv", "filename",
            "^(.*)$", "uuuu-MM-dd", false, "", ",", "UTF-8", List.of());
    }
}
