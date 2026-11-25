package com.metric.config.repository;

import com.metric.config.entity.PlatformConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformConfigRepository extends JpaRepository<PlatformConfig, Long> {

    Optional<PlatformConfig> findByConfigKey(String configKey);

    Optional<PlatformConfig> findFirstByConfigKeyOrderByUpdateTimeDesc(String configKey);
}
