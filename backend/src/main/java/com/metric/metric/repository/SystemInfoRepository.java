package com.metric.metric.repository;

import com.metric.metric.entity.SystemInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SystemInfoRepository extends JpaRepository<SystemInfo, Long> {

    Optional<SystemInfo> findBySystemCode(String systemCode);

    List<SystemInfo> findByStatus(Integer status);
}
