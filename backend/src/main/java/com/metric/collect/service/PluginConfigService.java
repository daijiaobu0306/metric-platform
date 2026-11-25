package com.metric.collect.service;

import com.metric.collect.entity.PluginConfig;
import com.metric.collect.repository.PluginConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class PluginConfigService {

    @Autowired
    private PluginConfigRepository pluginConfigRepository;

    public Page<PluginConfig> findAll(Pageable pageable) {
        return pluginConfigRepository.findAll(pageable);
    }

    public Page<PluginConfig> search(String name, String pluginType, Integer enabled, Pageable pageable) {
        return pluginConfigRepository.search(name, pluginType, enabled, pageable);
    }

    public Optional<PluginConfig> findById(Long id) {
        return pluginConfigRepository.findById(id);
    }

    public PluginConfig save(PluginConfig pluginConfig) {
        return pluginConfigRepository.save(pluginConfig);
    }

    public void deleteById(Long id) {
        pluginConfigRepository.deleteById(id);
    }

    public Optional<PluginConfig> updateDebugStatus(Long id, String status, String response) {
        return pluginConfigRepository.findById(id).map(config -> {
            config.setDebugStatus(status);
            config.setDebugResponse(response);
            config.setLastDebugTime(LocalDateTime.now());
            return pluginConfigRepository.save(config);
        });
    }

    public Optional<PluginConfig> toggleEnabled(Long id) {
        return pluginConfigRepository.findById(id).map(config -> {
            config.setEnabled(config.getEnabled() == 1 ? 0 : 1);
            return pluginConfigRepository.save(config);
        });
    }
}
