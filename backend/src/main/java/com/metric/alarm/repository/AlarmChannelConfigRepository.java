package com.metric.alarm.repository;

import com.metric.alarm.entity.AlarmChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlarmChannelConfigRepository extends JpaRepository<AlarmChannelConfig, Long> {

    Optional<AlarmChannelConfig> findByConfigKey(String configKey);

    List<AlarmChannelConfig> findByEnabled(Integer enabled);

    List<AlarmChannelConfig> findByChannelType(String channelType);
}
