package com.metric.collect.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.metric.gongdan.service.GongdanIntegrationService;

/**
 * 工单 ↔ BOMC 补推定时任务。
 *
 * <p>背景：内网 BOMC 偶发不可达时，建单会本地成功、推送失败。
 * 这里每 5 分钟把最近未推送成功的工单重推一次（单次上限 {@value #SCHEDULED_LIMIT} 条，
 * 避免 BOMC 长时间不可用时把调度线程拖死）。
 */
@Component
@ConditionalOnProperty(name = "bomc.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrationScheduler {

    /** 定时补推的单次上限 */
    private static final int SCHEDULED_LIMIT = 10;

    @Autowired
    private GongdanIntegrationService gongdanIntegrationService;

    @Scheduled(cron = "0 */5 * * * ?")
    public void syncPendingGongdan() {
        gongdanIntegrationService.pushPending(SCHEDULED_LIMIT);
    }
}
