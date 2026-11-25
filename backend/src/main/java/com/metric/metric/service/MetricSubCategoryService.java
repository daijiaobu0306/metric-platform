package com.metric.metric.service;

import com.metric.metric.entity.MetricSubCategory;
import com.metric.metric.repository.MetricSubCategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class MetricSubCategoryService {

    @Autowired
    private MetricSubCategoryRepository subCategoryRepository;

    public List<MetricSubCategory> getByCategoryId(Long categoryId) {
        return subCategoryRepository.findByCategoryId(categoryId);
    }

    public List<MetricSubCategory> getEnabledByCategoryId(Long categoryId) {
        return subCategoryRepository.findByCategoryIdAndStatus(categoryId, "enabled");
    }

    public Optional<MetricSubCategory> getById(Long id) {
        return subCategoryRepository.findById(id);
    }

    public MetricSubCategory save(MetricSubCategory subCategory) {
        return subCategoryRepository.save(subCategory);
    }

    @Transactional
    public void delete(Long id) {
        subCategoryRepository.deleteById(id);
    }

    public List<String> getSubCategoryCodes(Long categoryId) {
        return subCategoryRepository.findSubCategoryCodesByCategoryId(categoryId);
    }

    public boolean exists(Long categoryId, String subCategoryCode) {
        return subCategoryRepository.existsByCategoryIdAndSubCategoryCode(categoryId, subCategoryCode);
    }

    @Transactional
    public MetricSubCategory create(Long categoryId, String subCategoryCode, String subCategoryName, String description) {
        MetricSubCategory subCategory = new MetricSubCategory();
        subCategory.setCategoryId(categoryId);
        subCategory.setSubCategoryCode(subCategoryCode);
        subCategory.setSubCategoryName(subCategoryName);
        subCategory.setDescription(description);
        subCategory.setStatus("enabled");
        return subCategoryRepository.save(subCategory);
    }

    @Transactional
    public MetricSubCategory update(Long id, String subCategoryName, String description, String status) {
        Optional<MetricSubCategory> opt = subCategoryRepository.findById(id);
        if (opt.isPresent()) {
            MetricSubCategory subCategory = opt.get();
            if (subCategoryName != null) subCategory.setSubCategoryName(subCategoryName);
            if (description != null) subCategory.setDescription(description);
            if (status != null) subCategory.setStatus(status);
            return subCategoryRepository.save(subCategory);
        }
        return null;
    }
}
