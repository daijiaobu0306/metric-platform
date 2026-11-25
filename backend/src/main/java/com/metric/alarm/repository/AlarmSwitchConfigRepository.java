package com.metric.alarm.repository;

import com.metric.alarm.entity.AlarmSwitchConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AlarmSwitchConfigRepository extends JpaRepository<AlarmSwitchConfig, Long> {

    Optional<AlarmSwitchConfig> findByConfigKey(String configKey);
}
