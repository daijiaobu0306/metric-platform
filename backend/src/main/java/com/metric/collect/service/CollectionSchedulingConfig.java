package com.metric.collect.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class CollectionSchedulingConfig {
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("metric-scheduler-");
        return scheduler;
    }
    /** Legacy metric calculations must not delay directory scans on the shared scheduler. */
    @Bean(name = "collectionTaskScheduler")
    public ThreadPoolTaskScheduler collectionTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("collection-scan-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
