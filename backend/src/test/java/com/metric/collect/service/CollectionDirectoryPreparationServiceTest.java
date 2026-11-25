package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionDirectoryPreparationServiceTest {
    @TempDir Path root;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private CollectionDirectoryPreparationService service() {
        var paths = mock(BasePathProvider.class);
        when(paths.getBasePath()).thenReturn(root.resolve("old-root").toString());
        return new CollectionDirectoryPreparationService(jdbc, mapper, new CollectionDirectoryGuard(jdbc, mapper, paths));
    }

    private Map<String, Object> legacy(String code, String inbox, String archive) {
        return Map.of("sub_category_code", code, "import_path", inbox, "archive_path", archive);
    }

    private Map<String,Object> task(String code,String inbox,String archive) {
        return Map.of("code",code,"config_json","{\"code\":\""+code+"\",\"name\":\"test\",\"parser\":\"python-worker\",\"importPath\":\""+inbox+"\",\"archivePath\":\""+archive+"\",\"enabled\":true}");
    }

    @Test void preparesCandidateRootAndPreservesFilesOnRepeatedSave() throws Exception {
        var row = task("test", "raw_data/import/test", "raw_data/archive/test");
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source WHERE enabled=1"))
                .thenReturn(List.of(row));
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source")).thenReturn(List.of(row));
        var service = service();
        var first = service.prepare(root.toString());
        assertEquals(2, ((List<?>) first.get("created")).size());
        Path file = root.resolve("raw_data/import/test/input.csv");
        Files.writeString(file, "original data");
        var again = service.prepare(root.toString());
        assertEquals(List.of(), again.get("created"));
        assertEquals(2, ((List<?>) again.get("existing")).size());
        assertEquals("original data", Files.readString(file));
        assertFalse(Files.exists(root.resolve("old-root")));
    }

    @Test void unsupportedLegacyIsDisabledWithoutCreatingItsDirectories() {
        when(jdbc.queryForList("SELECT sub_category_code,import_path,archive_path FROM metric_import_config WHERE enabled=1"))
                .thenReturn(List.of(legacy("payActive", "old/in", "old/out")));
        when(jdbc.queryForList("SELECT sub_category_code FROM metric_import_config WHERE enabled=1"))
                .thenReturn(List.of(Map.of("sub_category_code", "payActive")));
        var report = service().prepare(root.toString());
        assertEquals(0, report.get("channelCount"));
        assertEquals(List.of("payActive"), report.get("disabledLegacy"));
        assertFalse(Files.exists(root.resolve("old")));
        verify(jdbc).update("UPDATE metric_import_config SET enabled=0 WHERE sub_category_code=? AND enabled=1", "payActive");
    }

    @Test void fileBlockingDirectoryFailsWithPathBeforeDisablingAnything() throws Exception {
        Files.writeString(root.resolve("blocked"), "keep");
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source WHERE enabled=1"))
                .thenReturn(List.of(task("test", "blocked/in", "archive")));
        var error = assertThrows(IllegalArgumentException.class, () -> service().prepare(root.toString()));
        assertTrue(error.getMessage().contains(root.resolve("blocked/in").toString()));
        verify(jdbc, never()).queryForList("SELECT sub_category_code FROM metric_import_config WHERE enabled=1");
        assertEquals("keep", Files.readString(root.resolve("blocked")));
    }

    @Test void includesEnabledTaskButNotDisabledJsonAndPreventsEscape() throws Exception {
        String json = "{\"code\":\"task\",\"name\":\"test\",\"importPath\":\"raw_data/import/task\",\"archivePath\":\"raw_data/archive/task\",\"enabled\":true}";
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source WHERE enabled=1"))
                .thenReturn(List.of(Map.of("code", "task", "config_json", json)));
        var report = service().prepare(root.toString());
        assertEquals(1, report.get("channelCount"));
        assertTrue(Files.isDirectory(root.resolve("raw_data/archive/task")));
        assertThrows(IllegalArgumentException.class, () -> CollectionDirectoryPreparationService.resolveSource(root.toString(), "../escape"));
        assertThrows(IllegalArgumentException.class, () -> CollectionDirectoryPreparationService.resolveSource(root.toString(), root.resolve("absolute").toString()));
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source WHERE enabled=1"))
                .thenReturn(List.of(Map.of("code", "task", "config_json", json.replace("true", "false"))));
        assertTrue(service().enabledTargets().isEmpty());
    }

    @Test void checksOwnershipAtCandidateRootBeforeCreatingAnything() {
        var active = task("test", "in", "out");
        var reserved = legacy("performance", "in/nested", "reserved");
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source WHERE enabled=1"))
                .thenReturn(List.of(active));
        when(jdbc.queryForList("SELECT sub_category_code,import_path,archive_path FROM metric_import_config WHERE enabled=1"))
                .thenReturn(List.of(reserved));
        assertThrows(IllegalArgumentException.class, () -> service().prepare(root.toString()));
        assertFalse(Files.exists(root.resolve("in")));
    }

    @Test void healthChecksBothDirectoriesAndNeverCreatesThem() {
        var row = task("test", "missing/in", "missing/out");
        when(jdbc.queryForList("SELECT code,config_json FROM collection_source WHERE enabled=1"))
                .thenReturn(List.of(row));
        when(jdbc.queryForList("SELECT sub_category_code,import_path,archive_path FROM metric_import_config WHERE enabled=1"))
                .thenReturn(List.of(legacy("payActive", "unused/in", "unused/out")));
        var paths = mock(BasePathProvider.class);
        when(paths.getBasePath()).thenReturn(root.toString());
        var health = new ScanHealthService(service(), paths).checkHealth();
        assertEquals(false, health.get("healthy"));
        assertEquals(1, health.get("totalChecked"));
        assertEquals(1L, health.get("issueCount"));
        assertEquals(2, ((List<?>) health.get("issues")).size());
        assertFalse(Files.exists(root.resolve("missing")));
        verify(jdbc, never()).queryForList("SELECT sub_category_code FROM metric_import_config WHERE enabled=1");
    }
}
