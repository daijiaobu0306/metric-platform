package com.metric.collect.repository;

import com.metric.collect.entity.InnerStepResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InnerStepResponseRepository extends JpaRepository<InnerStepResponse, Long> {
}
