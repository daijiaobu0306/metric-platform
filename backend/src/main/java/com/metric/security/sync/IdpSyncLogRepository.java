package com.metric.security.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdpSyncLogRepository extends JpaRepository<IdpSyncLog, Long> {
}