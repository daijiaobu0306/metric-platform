package com.metric.mcp.repository;

import com.metric.mcp.entity.McpServerConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpServerConfigRepository extends JpaRepository<McpServerConfigEntity, Long> {

    Optional<McpServerConfigEntity> findByServerKey(String serverKey);

    List<McpServerConfigEntity> findAllByOrderByIdAsc();
}
