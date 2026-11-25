package com.metric.metric.service;

import com.metric.audit.entity.MetricAuditLog;
import com.metric.metric.entity.SystemInfo;
import com.metric.audit.repository.MetricAuditLogRepository;
import com.metric.security.service.CurrentUserProvider;
import com.metric.metric.repository.SystemInfoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class SystemService {

    @Autowired
    private SystemInfoRepository systemInfoRepository;

    @Autowired
    private MetricAuditLogRepository auditLogRepository;

    /**
     * 当前登录用户：用户触发的系统配置变更把真实操作人写进审计日志。
     * 无登录态时 operatorName() 回落 "system"，保持原兜底语义。
     */
    @Autowired
    private CurrentUserProvider currentUserProvider;

    public Page<SystemInfo> findAll(Pageable pageable) {
        return systemInfoRepository.findAll(pageable);
    }

    public List<SystemInfo> findAll() {
        return systemInfoRepository.findAll();
    }

    public List<SystemInfo> findByStatus(Integer status) {
        return systemInfoRepository.findByStatus(status);
    }

    public Optional<SystemInfo> findById(Long id) {
        return systemInfoRepository.findById(id);
    }

    public Optional<SystemInfo> findBySystemCode(String systemCode) {
        return systemInfoRepository.findBySystemCode(systemCode);
    }

    @Transactional
    public SystemInfo save(SystemInfo systemInfo) {
        boolean isUpdate = systemInfo.getId() != null;
        StringBuilder changeDesc = new StringBuilder();

        if (isUpdate) {
            Optional<SystemInfo> existingOpt = findById(systemInfo.getId());
            if (existingOpt.isPresent()) {
                SystemInfo existing = existingOpt.get();
                java.util.Map<String, String> changes = new java.util.LinkedHashMap<>();

                if (systemInfo.getSystemCode() != null && !systemInfo.getSystemCode().equals(existing.getSystemCode())) {
                    changes.put("系统编码", existing.getSystemCode() + " → " + systemInfo.getSystemCode());
                }
                if (systemInfo.getSystemName() != null && !systemInfo.getSystemName().equals(existing.getSystemName())) {
                    changes.put("系统名称", existing.getSystemName() + " → " + systemInfo.getSystemName());
                }
                if (systemInfo.getDescription() != null && !systemInfo.getDescription().equals(existing.getDescription())) {
                    changes.put("描述", existing.getDescription() + " → " + systemInfo.getDescription());
                }
                if (systemInfo.getStatus() != null && !systemInfo.getStatus().equals(existing.getStatus())) {
                    changes.put("状态", (existing.getStatus() == 1 ? "启用" : "禁用") + " → " + (systemInfo.getStatus() == 1 ? "启用" : "禁用"));
                }

                for (java.util.Map.Entry<String, String> entry : changes.entrySet()) {
                    if (changeDesc.length() > 0) changeDesc.append("；");
                    changeDesc.append(entry.getKey()).append(": ").append(entry.getValue());
                }

                if (changes.isEmpty()) {
                    changeDesc.append("未检测到字段变更");
                }
            }
        }

        SystemInfo saved = systemInfoRepository.save(systemInfo);

        // 记录审计日志
        MetricAuditLog log = new MetricAuditLog();
        log.setOperationType(isUpdate ? "修改" : "新增");
        log.setEntityType("system_info");
        log.setEntityId(saved.getId());
        log.setChangeContent((isUpdate ? "修改" : "新增") + "系统【" + saved.getSystemName() + "】: " + changeDesc.toString());
        log.setOperator(currentUserProvider.operatorName());
        auditLogRepository.save(log);

        return saved;
    }

    @Transactional
    public void deleteById(Long id) {
        Optional<SystemInfo> systemInfo = systemInfoRepository.findById(id);
        systemInfoRepository.deleteById(id);

        // 记录审计日志
        if (systemInfo.isPresent()) {
            MetricAuditLog log = new MetricAuditLog();
            log.setOperationType("删除");
            log.setEntityType("system_info");
            log.setEntityId(id);
            log.setChangeContent("删除系统: " + systemInfo.get().getSystemName());
            log.setOperator(currentUserProvider.operatorName());
            auditLogRepository.save(log);
        }
    }

    public void deleteAll() {
        systemInfoRepository.deleteAll();
    }
}
