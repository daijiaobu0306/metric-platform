package com.metric.metric.service;

import com.metric.metric.entity.SystemJhd;
import com.metric.metric.repository.SystemJhdRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SystemJhdImportService {

    @Autowired
    private SystemJhdRepository systemJhdRepository;

    /**
     * 导入 txt 文件
     * @param file 文件
     * @param sourceFileName 原始文件名（如 system_jhd_20260407.txt）
     */
    @Transactional
    public int importTxt(MultipartFile file, String sourceFileName) throws IOException {
        // 从文件名提取日期：system_jhd_20260407.txt -> 20260407 -> 2026-04-07
        LocalDate dataDate = parseDateFromFileName(sourceFileName);
        int count = 0;

        System.out.println("[SystemJhdImport] Starting import from file: " + sourceFileName + ", size: " + file.getSize());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                processLine(line, dataDate, sourceFileName);
                count++;
            }
        }
        System.out.println("[SystemJhdImport] Import complete, processed " + count + " lines");
        return count;
    }

    private LocalDate parseDateFromFileName(String fileName) {
        // 文件名格式: system_jhd_YYYYMMDD.txt
        try {
            String dateStr = fileName.replace("system_jhd_", "").replace(".txt", "").trim();
            if (dateStr.length() == 8) {
                int year = Integer.parseInt(dateStr.substring(0, 4));
                int month = Integer.parseInt(dateStr.substring(4, 6));
                int day = Integer.parseInt(dateStr.substring(6, 8));
                return LocalDate.of(year, month, day);
            }
        } catch (Exception e) {
            System.out.println("[SystemJhdImport] Failed to parse date from filename: " + fileName);
        }
        return LocalDate.now();
    }

    private void processLine(String line, LocalDate dataDate, String sourceFileName) {
        String[] fields = line.split("@", -1);

        // 增量入库：根据 businessModule, checkCode, abnormalDate 判断是否存在
        String businessModule = getField(fields, 3); // fields[3]=business_module/check_code(如BOSS46)
        String checkCode = getField(fields, 3);       // 与businessModule相同
        String abnormalDate = getField(fields, 8);    // fields[8]=abnormal_date(20260512)

        Optional<SystemJhd> existing = systemJhdRepository.findByBusinessModuleAndCheckCodeAndAbnormalDate(
            businessModule, checkCode, abnormalDate);

        SystemJhd entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new SystemJhd();
        }

        // 设置字段 - 根据TXT文件实际字段位置（@分隔，逐行分析）：
        // [0]=first_classify,[1]=second_classify,[2]=empty,
        // [3]=business_module(=check_code),[4]=check_name,
        // [5]=check_rule,[6]=empty,[7]=empty,[8]=abnormal_date,[9]=abnormal_count,
        // [10]=business_owner,[11]=current_step,[12]=empty,[13]=empty,[14]=order_2month,
        // [15]=unclosed_2month,[16]=second_classify,[17]=develop_time,[18]=create_time(部门),
        // [19]=reserve1(yyyymm),[20]=reserve2,[21]=close_order_count,[22]=close_order_rate,
        // [23]=timely_close_rate,[24]=need_rectify_total,[25]=rectif_complete_count,
        // [26]=rectif_complete_rate,[27]=rectif_timely_rate
        entity.setFirstClassify(getField(fields, 0));      // first_classify
        entity.setSecondClassify(getField(fields, 1));    // second_classify
        entity.setBusinessModule(businessModule);
        entity.setCheckCode(checkCode);
        entity.setCheckName(getField(fields, 4));
        entity.setCheckRule(getField(fields, 5));
        entity.setCheckOptimize(getField(fields, 6));
        entity.setCheckFreq(getField(fields, 7));        // fields[7]=check_freq(日/月)
        entity.setAbnormalDate(abnormalDate);            // fields[8]=abnormal_date
        entity.setAbnormalCount(getField(fields, 9));    // fields[9]=abnormal_count
        entity.setBusinessOwner(getField(fields, 10));   // fields[10]=business_owner
        entity.setCurrentStep(getField(fields, 11));      // fields[11]=current_step
        entity.setCheckStatus(getField(fields, 14));      // fields[14]=check_status
        entity.setVerifyResult(getField(fields, 1));      // fields[1]=second_classify实际值但VERIFY_RESULT
        entity.setOrder2month(getField(fields, 14));      // fields[14]=order_2month
        entity.setUnclosed2month(getField(fields, 15));   // fields[15]=unclosed_2month
        entity.setCreateTime(getField(fields, 17));      // fields[17]=datetime -> create_time
        entity.setDevelopTime(getField(fields, 18));      // fields[18]=department -> develop_time
        entity.setReserve1(getField(fields, 19));        // fields[19]=yyyymm
        entity.setReserve2(getField(fields, 20));
        entity.setSourceFile(sourceFileName);
        // 新增字段
        entity.setCloseOrderCount(getField(fields, 21));
        entity.setCloseOrderRate(getField(fields, 22));
        entity.setTimelyCloseRate(getField(fields, 23));
        entity.setNeedRectifyTotal(getField(fields, 24));
        entity.setRectifCompleteCount(getField(fields, 25));
        entity.setRectifCompleteRate(getField(fields, 26));
        entity.setRectifTimelyRate(getField(fields, 27));
        entity.setInsertTime(LocalDateTime.now());

        systemJhdRepository.save(entity);
    }

    private String getField(String[] fields, int index) {
        if (index < fields.length) {
            String val = fields[index];
            return val != null ? val.trim() : null;
        }
        return null;
    }
}
