package com.metric.collect.repository;

import com.metric.collect.entity.PluginAuthentication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginAuthenticationRepository extends JpaRepository<PluginAuthentication, Long> {
    Page<PluginAuthentication> findByNameContainingIgnoreCase(String name, Pageable pageable);

    List<PluginAuthentication> findByName(String name);

    Optional<PluginAuthentication> findFirstByName(String name);
}
