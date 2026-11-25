package com.metric.collect.service;

import com.metric.collect.entity.PluginAuthentication;
import com.metric.collect.repository.PluginAuthenticationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PluginAuthenticationService {

    @Autowired
    private PluginAuthenticationRepository repository;

    public Page<PluginAuthentication> search(String name, Pageable pageable) {
        if (name == null || name.isBlank()) {
            return repository.findAll(pageable);
        }
        return repository.findByNameContainingIgnoreCase(name, pageable);
    }

    public Optional<PluginAuthentication> findById(Long id) {
        return repository.findById(id);
    }

    public Optional<PluginAuthentication> findByName(String name) {
        return repository.findFirstByName(name);
    }

    public List<PluginAuthentication> findAll() {
        return repository.findAll();
    }

    public PluginAuthentication save(PluginAuthentication auth) {
        return repository.save(auth);
    }

    public void deleteById(Long id) {
        repository.deleteById(id);
    }
}
