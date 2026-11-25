package com.metric.collect.repository;

import com.metric.collect.entity.Prompt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromptRepository extends JpaRepository<Prompt, Long> {
    Page<Prompt> findByNameContaining(String name, Pageable pageable);
}
