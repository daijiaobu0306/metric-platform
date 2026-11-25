package com.metric.alarm.repository;

import com.metric.alarm.entity.AlarmAnalysisReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AlarmAnalysisReportRepository extends JpaRepository<AlarmAnalysisReport, Long> {

    List<AlarmAnalysisReport> findByReportDateOrderByIdDesc(LocalDate reportDate);

    List<AlarmAnalysisReport> findByReportDateBetweenOrderByReportDateDesc(LocalDate startDate, LocalDate endDate);

    @Query("SELECT a FROM AlarmAnalysisReport a WHERE a.metricName = :metricName ORDER BY a.reportDate DESC")
    List<AlarmAnalysisReport> findByMetricNameOrderByReportDateDesc(@Param("metricName") String metricName);

    void deleteByReportDate(LocalDate reportDate);

    Page<AlarmAnalysisReport> findAllByOrderByReportDateDesc(Pageable pageable);

    Page<AlarmAnalysisReport> findByReportDateBetweenOrderByReportDateDesc(LocalDate startDate, LocalDate endDate, Pageable pageable);
}
