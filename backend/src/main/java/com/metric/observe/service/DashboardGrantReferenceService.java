package com.metric.observe.service;

import com.metric.observe.repository.DashboardViewConfigRepository;
import org.springframework.stereotype.Service;

@Service
public class DashboardGrantReferenceService {
    private final DashboardViewConfigRepository views;
    private final DashboardAccessService access;

    public DashboardGrantReferenceService(DashboardViewConfigRepository views, DashboardAccessService access) {
        this.views = views;
        this.access = access;
    }

    public boolean references(String subjectType, Long id) {
        return views.findAll().stream().anyMatch(view -> {
            var policy = access.policy(view);
            return policy != null && (policy.grants().stream().anyMatch(grant ->
                    subjectType.equals(grant.subjectType()) && id.equals(grant.subjectId()))
                    || ("user".equals(subjectType) && policy.supervisorIds().contains(id)));
        });
    }
}
