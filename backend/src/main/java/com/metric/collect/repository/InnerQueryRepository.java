package com.metric.collect.repository;

import com.metric.collect.entity.InnerQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InnerQueryRepository extends JpaRepository<InnerQuery, Long> {
}
