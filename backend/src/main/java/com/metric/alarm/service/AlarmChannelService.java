package com.metric.alarm.service;

import com.metric.alarm.entity.AlarmChannelConfig;
import com.metric.alarm.repository.AlarmChannelConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AlarmChannelService {

    @Autowired
    private AlarmChannelConfigRepository alarmChannelConfigRepository;

    public List<AlarmChannelConfig> findAll() {
        return alarmChannelConfigRepository.findAll();
    }

    public List<AlarmChannelConfig> findByEnabled() {
        return alarmChannelConfigRepository.findByEnabled(1);
    }

    public Optional<AlarmChannelConfig> findById(Long id) {
        return alarmChannelConfigRepository.findById(id);
    }

    public AlarmChannelConfig save(AlarmChannelConfig config) {
        return alarmChannelConfigRepository.save(config);
    }

    public void delete(Long id) {
        alarmChannelConfigRepository.deleteById(id);
    }

    public AlarmChannelConfig toggle(Long id, Integer enabled) {
        AlarmChannelConfig config = alarmChannelConfigRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Channel not found"));
        config.setEnabled(enabled);
        return alarmChannelConfigRepository.save(config);
    }
}
