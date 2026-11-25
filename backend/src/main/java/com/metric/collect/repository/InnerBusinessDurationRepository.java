package com.metric.collect.repository;

import com.metric.collect.entity.InnerBusinessDuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InnerBusinessDurationRepository extends JpaRepository<InnerBusinessDuration, Long> {
}
