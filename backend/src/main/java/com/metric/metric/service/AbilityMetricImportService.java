package com.metric.metric.service;

import com.metric.metric.entity.AbilityMetric;
import com.metric.metric.repository.AbilityMetricRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AbilityMetricImportService {

    private static final String SEPARATOR = "€€";

    @Autowired
    private AbilityMetricRepository abilityMetricRepository;

    /**
     * 导入 txt 文件
     * 文件名格式: D_290_F30004_A129000001_29001_YYYYMMDD_00.txt
     */
    @Transactional
    public int importTxt(MultipartFile file, String sourceFileName) throws IOException {
        String[] fileNameParts = parseFileName(sourceFileName);
        if (fileNameParts == null) {
            throw new IOException("Invalid file name format: " + sourceFileName);
        }

        String regionCode = fileNameParts[1];      // 290
        String functionCode = fileNameParts[2];     // F30004
        String capabilityCode = fileNameParts[3];   // A129000001
        String serviceOrgCode = fileNameParts[4];  // 29001
        String dataDate = fileNameParts[5];        // YYYYMMDD

        System.out.println("[AbilityMetricImport] Starting import from file: " + sourceFileName);
        System.out.println("[AbilityMetricImport] Region: " + regionCode + ", Function: " + functionCode +
            ", Capability: " + capabilityCode + ", ServiceOrg: " + serviceOrgCode + ", Date: " + dataDate);

        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;
                processLine(line, lineNumber, regionCode, functionCode, capabilityCode, serviceOrgCode, dataDate, sourceFileName);
                count++;
            }
        }
        System.out.println("[AbilityMetricImport] Import complete, processed " + count + " lines");
        return count;
    }

    private String[] parseFileName(String fileName) {
        // D_290_F30004_A129000001_29001_20260501_00.txt
        String nameWithoutExt = fileName.replace(".txt", "");
        String[] parts = nameWithoutExt.split("_");
        if (parts.length < 7) {
            return null;
        }
        return parts;
    }

    private void processLine(String line, int lineNumber, String regionCode, String functionCode,
                           String capabilityCode, String serviceOrgCode, String dataDate, String sourceFileName) {
        String[] fields = line.split(SEPARATOR, -1);

        if (fields.length < 15) {
            System.out.println("[AbilityMetricImport] Line " + lineNumber + " has insufficient fields: " + fields.length);
            return;
        }

        String unifiedSceneCode = getField(fields, 2);
        String unifiedServiceCode = getField(fields, 4);
        String abilityUserCode = getField(fields, 6);
        String uniqueKey = dataDate + "_" + capabilityCode + "_" + unifiedServiceCode + "_" + abilityUserCode + "_" + lineNumber;

        Optional<AbilityMetric> existing = abilityMetricRepository.findByUniqueKey(uniqueKey);
        AbilityMetric entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new AbilityMetric();
        }

        entity.setFileName(sourceFileName);
        entity.setRegionCode(regionCode);
        entity.setFunctionCode(functionCode);
        entity.setUnifiedCapabilityCode(capabilityCode);
        entity.setServiceOrgCode(serviceOrgCode);
        entity.setDataDate(dataDate);
        entity.setLineNumber(lineNumber);
        entity.setUnifiedSceneCode(unifiedSceneCode);
        entity.setUnifiedServiceCode(unifiedServiceCode);
        entity.setAbilityUserName(getField(fields, 5));
        entity.setAbilityUserCode(abilityUserCode);
        entity.setAbilityUserAppName(getField(fields, 7));
        entity.setAbilityUserAppCode(getField(fields, 8));
        entity.setAbilityUserOrgName(getField(fields, 9));
        entity.setAbilityUserOrgCode(getField(fields, 10));
        entity.setPortalOrderNo(getField(fields, 11));
        entity.setPortalSubOrderNo(getField(fields, 12));
        entity.setInvokeVolume(getField(fields, 13));
        entity.setInvokeFailure(getField(fields, 14));
        entity.setUniqueKey(uniqueKey);
        entity.setInsertTime(LocalDateTime.now());

        abilityMetricRepository.save(entity);
    }

    private String getField(String[] fields, int index) {
        if (index < fields.length) {
            String val = fields[index];
            return val != null ? val.trim() : null;
        }
        return null;
    }
}
