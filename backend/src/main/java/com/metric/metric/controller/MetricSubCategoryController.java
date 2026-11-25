package com.metric.metric.controller;

import com.metric.metric.entity.MetricSubCategory;
import com.metric.metric.service.MetricSubCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metric/subcategory")
public class MetricSubCategoryController {

    @Autowired
    private MetricSubCategoryService subCategoryService;

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) Long categoryId) {
        List<MetricSubCategory> list;
        if (categoryId != null) {
            list = subCategoryService.getByCategoryId(categoryId);
        } else {
            list = subCategoryService.getByCategoryId(categoryId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        subCategoryService.getById(id).ifPresentOrElse(
            sc -> {
                result.put("code", 200);
                result.put("data", sc);
            },
            () -> {
                result.put("code", 404);
                result.put("message", "未找到");
            }
        );
        return result;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody MetricSubCategory subCategory) {
        Map<String, Object> result = new HashMap<>();
        MetricSubCategory saved = subCategoryService.save(subCategory);
        result.put("code", 200);
        result.put("data", saved);
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody MetricSubCategory subCategory) {
        Map<String, Object> result = new HashMap<>();
        MetricSubCategory updated = subCategoryService.update(id, subCategory.getSubCategoryName(),
            subCategory.getDescription(), subCategory.getStatus());
        if (updated != null) {
            result.put("code", 200);
            result.put("data", updated);
        } else {
            result.put("code", 404);
            result.put("message", "未找到");
        }
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        subCategoryService.delete(id);
        result.put("code", 200);
        return result;
    }

    @GetMapping("/codes")
    public Map<String, Object> getCodes(@RequestParam Long categoryId) {
        Map<String, Object> result = new HashMap<>();
        List<String> codes = subCategoryService.getSubCategoryCodes(categoryId);
        result.put("code", 200);
        result.put("data", codes);
        return result;
    }
}
