package com.metric.metric.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.metric.entity.AbilityMetric;
import com.metric.metric.repository.AbilityMetricRepository;
import com.metric.metric.service.AbilityMetricImportService;
import com.metric.metric.service.AbilityMetricScanScheduler;
import com.metric.metric.service.AbilityMetricStatisticsService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ability-metric")
public class AbilityMetricController {

    @Autowired
    private AbilityMetricRepository abilityMetricRepository;

    @Autowired
    private AbilityMetricImportService abilityMetricImportService;

    @Autowired
    private AbilityMetricStatisticsService statisticsService;

    @Autowired
    private AbilityMetricScanScheduler scanScheduler;

    @GetMapping("/list")
    public ApiResponse<Page<AbilityMetric>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String dataDateStart,
            @RequestParam(required = false) String dataDateEnd,
            @RequestParam(required = false) String capabilityCode,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String serviceOrgCode) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "dataDate", "lineNumber"));
        Specification<AbilityMetric> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (dataDateStart != null && !dataDateStart.isEmpty()) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dataDate"), dataDateStart));
            }
            if (dataDateEnd != null && !dataDateEnd.isEmpty()) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dataDate"), dataDateEnd));
            }
            if (capabilityCode != null && !capabilityCode.isEmpty()) {
                predicates.add(cb.equal(root.get("unifiedCapabilityCode"), capabilityCode));
            }
            if (userCode != null && !userCode.isEmpty()) {
                predicates.add(cb.equal(root.get("abilityUserCode"), userCode));
            }
            if (serviceOrgCode != null && !serviceOrgCode.isEmpty()) {
                predicates.add(cb.equal(root.get("serviceOrgCode"), serviceOrgCode));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AbilityMetric> result = abilityMetricRepository.findAll(spec, pageable);
        return ApiResponse.success(result);
    }

    @GetMapping("/statistics")
    public ApiResponse<List<Map<String, Object>>> statistics(
            @RequestParam(defaultValue = "capability") String dimension,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String capabilityCode,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String functionCode,
            @RequestParam(required = false) String serviceOrgCode,
            @RequestParam(required = false) String sceneCode) {

        List<Map<String, Object>> stats = statisticsService.getStatisticsByDimension(
            dimension, startDate, endDate, capabilityCode, userCode, regionCode, functionCode, serviceOrgCode, sceneCode);
        return ApiResponse.success(stats);
    }

    @GetMapping("/yoy-mom")
    public ApiResponse<Map<String, Object>> getYoYMoM(
            @RequestParam(required = false) String capabilityCode,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) String currentDate,
            @RequestParam(defaultValue = "day") String period) {

        if (currentDate == null || currentDate.isEmpty()) {
            currentDate = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        }
        Map<String, Object> result = statisticsService.getYoYMoMAnalysis(
            capabilityCode, userCode, currentDate, period);
        return ApiResponse.success(result);
    }

    @GetMapping("/export")
    public ApiResponse<Map<String, Object>> export(
            @RequestParam(required = false) String dataDateStart,
            @RequestParam(required = false) String dataDateEnd,
            @RequestParam(required = false) String capabilityCode,
            @RequestParam(required = false) String userCode) {

        Specification<AbilityMetric> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (dataDateStart != null && !dataDateStart.isEmpty()) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("dataDate"), dataDateStart));
            }
            if (dataDateEnd != null && !dataDateEnd.isEmpty()) {
                predicates.add(cb.lessThanOrEqualTo(root.get("dataDate"), dataDateEnd));
            }
            if (capabilityCode != null && !capabilityCode.isEmpty()) {
                predicates.add(cb.equal(root.get("unifiedCapabilityCode"), capabilityCode));
            }
            if (userCode != null && !userCode.isEmpty()) {
                predicates.add(cb.equal(root.get("abilityUserCode"), userCode));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        List<AbilityMetric> data = abilityMetricRepository.findAll(spec);
        Map<String, Object> result = new HashMap<>();
        result.put("count", data.size());
        result.put("data", data);
        return ApiResponse.success(result);
    }

    @PostMapping("/scan")
    public ApiResponse<String> triggerScan() {
        scanScheduler.scanAndImport();
        return ApiResponse.success("扫描任务已触发");
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importFile(@RequestParam("file") MultipartFile file) throws IOException {
        int count = abilityMetricImportService.importTxt(file, file.getOriginalFilename());
        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("fileName", file.getOriginalFilename());
        return ApiResponse.success(result);
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<String>> getCapabilities() {
        List<String> capabilities = abilityMetricRepository.findAll().stream()
            .map(AbilityMetric::getUnifiedCapabilityCode)
            .distinct()
            .sorted()
            .toList();
        return ApiResponse.success(capabilities);
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, String>>> getUsers() {
        List<Map<String, String>> users = abilityMetricRepository.findAll().stream()
            .map(a -> {
                Map<String, String> m = new HashMap<>();
                m.put("code", a.getAbilityUserCode());
                m.put("name", a.getAbilityUserName());
                return m;
            })
            .distinct()
            .sorted((a, b) -> a.get("code").compareTo(b.get("code")))
            .toList();
        return ApiResponse.success(users);
    }
}