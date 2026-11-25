package com.metric.metric.service;

import com.metric.metric.entity.MetricImportConfig;
import org.springframework.stereotype.Service;
import java.io.File;

/** Reserved integration point: no file is consumed before a real parser is implemented. */
@Service
public class DemandImportService {
    public int importFile(File file, MetricImportConfig config) {
        throw new IllegalArgumentException(LegacyImportSupport.unavailableMessage(config.getSubCategoryCode()));
    }
}
