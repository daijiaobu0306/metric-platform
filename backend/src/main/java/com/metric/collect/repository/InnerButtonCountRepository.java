package com.metric.collect.repository;

import com.metric.collect.entity.InnerButtonCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InnerButtonCountRepository extends JpaRepository<InnerButtonCount, Long> {
}
