package com.metric.collect.repository;

import com.metric.collect.entity.PluginConfig;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PluginConfigRepository extends JpaRepository<PluginConfig, Long> {

    Page<PluginConfig> findByEnabled(Integer enabled, Pageable pageable);

    List<PluginConfig> findByEnabledAndTriggerMode(Integer enabled, String triggerMode);

    List<PluginConfig> findByTriggerMode(String triggerMode);

    @Query("SELECT p FROM PluginConfig p WHERE " +
            "(:name IS NULL OR p.name LIKE :name) AND " +
            "(:pluginType IS NULL OR p.pluginType = :pluginType) AND " +
            "(:enabled IS NULL OR p.enabled = :enabled)")
    Page<PluginConfig> search(
            @Param("name") String name,
            @Param("pluginType") String pluginType,
            @Param("enabled") Integer enabled,
            Pageable pageable);
}
