package com.metric.metric.service;

import com.metric.metric.entity.SystemJhdDetail;
import com.metric.metric.repository.SystemJhdDetailRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SystemJhdDetailImportService {

    @Autowired
    private SystemJhdDetailRepository systemJhdDetailRepository;

    /**
     * 导入 txt 文件
     * @param file 文件
     * @param sourceFileName 原始文件名（如 system_jhd_detail_20260513.txt）
     */
    @Transactional
    public int importTxt(MultipartFile file, String sourceFileName) throws IOException {
        int count = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                processLine(line, sourceFileName);
                count++;
            }
        }
        return count;
    }

    private void processLine(String line, String sourceFileName) {
        String[] fields = line.split("@", -1);

        // 增量入库：根据 wsId (工单编码) 和 auditId (稽核点编号) 判断是否存在
        String wsId = getField(fields, 3);   // 第四个字段是 WS_ID (工单编码)
        String auditId = getField(fields, 4); // 第五个字段是 AUDIT_ID (稽核点编号)

        if (wsId == null || wsId.isEmpty()) {
            return; // 跳过无效数据
        }

        Optional<SystemJhdDetail> existing = systemJhdDetailRepository.findByWsIdAndAuditId(wsId, auditId);

        SystemJhdDetail entity;
        if (existing.isPresent()) {
            entity = existing.get();
        } else {
            entity = new SystemJhdDetail();
        }

        // 设置字段 (根据字段说明文档的顺序)
        entity.setDepartment(getField(fields, 0));           // 01 部门
        entity.setFirstLevelCategory(getField(fields, 1));   // 02 一级分类
        entity.setSecondLevelCategory(getField(fields, 2));  // 03 二级分类
        entity.setWsId(wsId);                               // 04 工单编码
        entity.setAuditId(auditId);                         // 05 稽核点编号
        entity.setAuditName(getField(fields, 5));           // 06 稽核点名称
        entity.setBusinessSupervisorName(getField(fields, 6)); // 07 负责主管
        entity.setDealPerson(getField(fields, 7));          // 08 当前处理人
        entity.setDealPersonName(getField(fields, 8));      // 09 当前处理人姓名
        entity.setStatus(getField(fields, 9));              // 10 工单处理状态
        entity.setIfOk(getField(fields, 10));               // 11 是否完工
        entity.setCreateDate(getField(fields, 11));         // 12 工单派发时间
        entity.setModifyDate(getField(fields, 12));          // 13 工单实际处理时间
        entity.setLimitDate(getField(fields, 13));          // 14 处理逾期时间
        entity.setIfLimitOk(getField(fields, 14));          // 15 是否处理逾期标识
        entity.setRemark(getField(fields, 15));             // 16 处理信息
        entity.setUpdateTime(getField(fields, 16));         // 17 更新时间
        entity.setIsNeedRectify(getField(fields, 17));      // 18 是否需要整改
        entity.setRectifyReason(getField(fields, 18));      // 19 整改原因分类
        entity.setRectifyStatus(getField(fields, 19));      // 20 整改状态
        entity.setFixSituation(getField(fields, 20));       // 21 修复情况
        entity.setFixCount(getField(fields, 21));           // 22 修复数量
        entity.setRectifyDeadline(getField(fields, 22));   // 23 预计整改完成时间
        entity.setRectifyDeadlineSj(getField(fields, 23)); // 24 实际整改完成时间
        entity.setIsOverdue(getField(fields, 24));          // 25 整改是否逾期
        entity.setRsvrStr1(getField(fields, 25));           // 26 工单月份
        entity.setRsvrStr2(getField(fields, 26));           // 27 预留字段2
        entity.setRsvrStr3(getField(fields, 27));            // 28 预留字段3
        entity.setRsvrStr4(getField(fields, 28));           // 29 预留字段4
        entity.setRsvrStr5(getField(fields, 29));           // 30 预留字段5
        entity.setRsvrStr6(getField(fields, 30));           // 31 预留字段6
        entity.setSourceFile(sourceFileName);
        entity.setInsertTime(LocalDateTime.now());

        systemJhdDetailRepository.save(entity);
    }

    private String getField(String[] fields, int index) {
        if (index < fields.length) {
            String val = fields[index];
            return val != null ? val.trim() : null;
        }
        return null;
    }
}