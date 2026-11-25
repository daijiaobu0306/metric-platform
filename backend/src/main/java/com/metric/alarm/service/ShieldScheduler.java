package com.metric.alarm.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShieldScheduler {

    @Autowired
    private AlarmService alarmService;

    @Scheduled(cron = "0 */5 * * * ?")
    public void processExpiredShields() {
        alarmService.processExpiredShields();
    }
}
