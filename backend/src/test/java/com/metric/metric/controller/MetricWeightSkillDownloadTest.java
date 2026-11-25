package com.metric.metric.controller;

import com.metric.security.entity.SysUser;
import com.metric.security.service.CurrentUserProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricWeightSkillDownloadTest {
    @Test
    void downloadsBothSelfContainedChineseSkillsWithInstructions() throws Exception {
        var users = mock(CurrentUserProvider.class);
        var admin = new SysUser();
        admin.setIsAdmin(1);
        when(users.currentUser()).thenReturn(Optional.of(admin));
        var response = new MetricWeightReviewController(null, users).downloadSkills();

        var files = new HashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertNull(files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }

        var skills = Map.of(
                "指标平台-专家基础权重", List.of("catalog.md"),
                "指标平台-月度动态权重", List.of("monthly-data.md", "weight-rules.md"));
        assertEquals(9, files.size());
        for (var skill : skills.entrySet()) {
            var prefix = skill.getKey() + "/";
            assertTrue(files.get(prefix + "SKILL.md").startsWith("---"));
            assertTrue(files.get(prefix + "使用说明.txt").contains("一般输入"));
            assertTrue(files.get(prefix + "references/write-contract.md").contains("START TRANSACTION"));
            for (var reference : skill.getValue()) {
                assertFalse(files.get(prefix + "references/" + reference).isBlank());
            }
        }
    }
}
