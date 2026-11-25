package com.metric.metric.repository;

import com.metric.metric.entity.DataBackup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataBackupRepository extends JpaRepository<DataBackup, Long> {

    List<DataBackup> findAllByOrderByBackupTimeDesc();
}
