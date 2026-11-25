package com.metric.collect.service;

import com.metric.metric.entity.SystemJhd;
import com.metric.metric.entity.SystemJhdDetail;
import com.metric.metric.repository.SystemJhdRepository;
import com.metric.metric.repository.SystemJhdDetailRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;

/**
 * 把合规脚本采集的原始行回写 system_jhd / system_jhd_detail 专表，供数据汇聚页读取。
 *
 * 数据汇聚页仍直连专表（SELECT * FROM system_jhd / system_jhd_detail），
 * 采集方式则统一走「脚本 + collection_source 采集任务」；本服务在提交批次时把
 * 脚本带出的原始列按业务键 upsert 到专表（沿用老导入服务的去重键），
 * 不改变 collection_row / metric_realtime 的指标链路。
 */
@Service
public class ComplianceDedicatedTableIngestionService {

    private final SystemJhdRepository jhd;
    private final SystemJhdDetailRepository detail;

    public ComplianceDedicatedTableIngestionService(SystemJhdRepository jhd, SystemJhdDetailRepository detail) {
        this.jhd = jhd;
        this.detail = detail;
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 月数据清单 system_jhd：去重键 business_module=check_code + abnormal_date。 */
    @Transactional
    public int storeMonthly(String sourceFile, List<Map<String, Object>> rows) {
        int count = 0;
        for (Map<String, Object> row : rows) {
            String code = str(row, "metric_code");
            String abnormalDate = str(row, "abnormal_date");
            // 专表以 check_code + abnormal_date 为业务键；键缺失时跳过，不写入无主记录。
            if (code == null || code.isBlank() || abnormalDate == null || abnormalDate.isBlank()) continue;
            SystemJhd entity = jhd.findByBusinessModuleAndCheckCodeAndAbnormalDate(code, code, abnormalDate)
                    .orElseGet(SystemJhd::new);
            entity.setFirstClassify(str(row, "first_classify"));
            entity.setSecondClassify(str(row, "second_classify"));
            entity.setBusinessModule(code);
            entity.setCheckCode(code);
            entity.setCheckName(str(row, "check_name"));
            entity.setCheckRule(str(row, "check_rule"));
            entity.setCheckOptimize(str(row, "check_optimize"));
            entity.setCheckFreq(str(row, "check_freq"));
            entity.setAbnormalDate(abnormalDate);
            entity.setAbnormalCount(str(row, "abnormal_count"));
            entity.setBusinessOwner(str(row, "business_owner"));
            entity.setCurrentStep(str(row, "current_step"));
            entity.setCheckStatus(str(row, "check_status"));
            entity.setVerifyResult(str(row, "verify_result"));
            entity.setOrder2month(str(row, "order_2month"));
            entity.setUnclosed2month(str(row, "unclosed_2month"));
            entity.setDevelopTime(str(row, "develop_time"));
            entity.setCreateTime(str(row, "create_time"));
            entity.setReserve1(str(row, "reserve1"));
            entity.setReserve2(str(row, "reserve2"));
            entity.setCloseOrderCount(str(row, "close_order_count"));
            entity.setCloseOrderRate(str(row, "close_order_rate"));
            entity.setTimelyCloseRate(str(row, "timely_close_rate"));
            entity.setNeedRectifyTotal(str(row, "need_rectify_total"));
            entity.setRectifCompleteCount(str(row, "rectif_complete_count"));
            entity.setRectifCompleteRate(str(row, "rectif_complete_rate"));
            entity.setRectifTimelyRate(str(row, "rectif_timely_rate"));
            entity.setSourceFile(sourceFile);
            entity.setInsertTime(LocalDateTime.now());
            jhd.save(entity);
            count++;
        }
        return count;
    }

    /** 工单明细 system_jhd_detail：去重键 ws_id + audit_id。 */
    @Transactional
    public int storeDetail(String sourceFile, List<Map<String, Object>> rows) {
        int count = 0;
        for (Map<String, Object> row : rows) {
            String wsId = str(row, "ws_id");
            String auditId = str(row, "audit_id");
            // 专表以 工单编码 + 稽核点编号 为业务键；键缺失时跳过，避免写入无法追溯的工单。
            if (wsId == null || wsId.isBlank()) continue;
            SystemJhdDetail entity = detail.findByWsIdAndAuditId(wsId, auditId)
                    .orElseGet(SystemJhdDetail::new);
            if (entity.getId() != null && olderDetail(sourceFile, row, entity)) continue;
            entity.setDepartment(str(row, "department"));
            entity.setFirstLevelCategory(str(row, "first_level_category"));
            entity.setSecondLevelCategory(str(row, "second_level_category"));
            entity.setWsId(wsId);
            entity.setAuditId(auditId);
            entity.setAuditName(str(row, "audit_name"));
            entity.setBusinessSupervisorName(str(row, "business_supervisor_name"));
            entity.setDealPerson(str(row, "deal_person"));
            entity.setDealPersonName(str(row, "deal_person_name"));
            entity.setStatus(str(row, "status"));
            entity.setIfOk(str(row, "if_ok"));
            entity.setCreateDate(str(row, "create_date"));
            entity.setModifyDate(str(row, "modify_date"));
            entity.setLimitDate(str(row, "limit_date"));
            entity.setIfLimitOk(str(row, "if_limit_ok"));
            entity.setRemark(str(row, "remark"));
            entity.setUpdateTime(str(row, "update_time"));
            entity.setIsNeedRectify(str(row, "is_need_rectify"));
            entity.setRectifyReason(str(row, "rectify_reason"));
            entity.setRectifyStatus(str(row, "rectify_status"));
            entity.setFixSituation(str(row, "fix_situation"));
            entity.setFixCount(str(row, "fix_count"));
            entity.setRectifyDeadline(str(row, "rectify_deadline"));
            entity.setRectifyDeadlineSj(str(row, "rectify_deadline_sj"));
            entity.setIsOverdue(str(row, "is_overdue"));
            entity.setRsvrStr1(str(row, "rsrv_str1"));
            entity.setRsvrStr2(str(row, "rsrv_str2"));
            entity.setRsvrStr3(str(row, "rsrv_str3"));
            entity.setRsvrStr4(str(row, "rsrv_str4"));
            entity.setRsvrStr5(str(row, "rsrv_str5"));
            entity.setRsvrStr6(str(row, "rsrv_str6"));
            entity.setSourceFile(sourceFile);
            entity.setInsertTime(LocalDateTime.now());
            detail.save(entity);
            count++;
        }
        return count;
    }

    // Source file date is already the raw-rows protocol business date. Never
    // compare ingestion time: a late historical delivery must stay historical.
    private static LocalDate detailDay(String file) {
        if (file == null) return null;
        var match = Pattern.compile("system_jhd_detail_(\\d{8})").matcher(file);
        if (!match.find()) return null;
        try { return LocalDate.parse(match.group(1), DateTimeFormatter.BASIC_ISO_DATE); }
        catch (DateTimeParseException e) { return null; }
    }

    private static LocalDateTime updatedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value.trim().replace(' ', 'T')); }
        catch (DateTimeParseException e) { return null; }
    }

    static boolean olderDetail(String file, Map<String,Object> row, SystemJhdDetail existing) {
        var incomingDay = detailDay(file);
        var existingDay = detailDay(existing.getSourceFile());
        if (incomingDay != null && existingDay != null && incomingDay.isBefore(existingDay)) return true;
        var incomingTime = updatedAt(str(row, "update_time"));
        var existingTime = updatedAt(existing.getUpdateTime());
        return incomingTime != null && existingTime != null && incomingTime.isBefore(existingTime);
    }
}
