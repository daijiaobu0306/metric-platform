package com.metric.gongdan.service;

import com.metric.collect.service.IntegrationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BomcLocalDeploymentTest {
    @Test void disabledClientReturnsBeforeBuildingOrSendingRequests() {
        var client = new BomcGongdanClient();
        ReflectionTestUtils.setField(client, "enabled", false);
        assertThat(client.createTask(null).isSuccess()).isFalse();
        assertThat(client.queryTask(null).isSuccess()).isFalse();
    }

    @Test void disabledLocalEnvironmentDoesNotRegisterRetryScheduler() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().registerSingleton(
                        "gongdanIntegrationService", mock(GongdanIntegrationService.class)))
                .withUserConfiguration(IntegrationScheduler.class)
                .withPropertyValues("bomc.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(IntegrationScheduler.class));
    }

    @Test void existingEnvironmentsKeepRetrySchedulerByDefault() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().registerSingleton(
                        "gongdanIntegrationService", mock(GongdanIntegrationService.class)))
                .withUserConfiguration(IntegrationScheduler.class)
                .run(context -> assertThat(context).hasSingleBean(IntegrationScheduler.class));
    }
}
