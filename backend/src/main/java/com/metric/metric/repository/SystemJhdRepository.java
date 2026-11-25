package com.metric.metric.repository;

import com.metric.metric.entity.SystemJhd;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SystemJhdRepository extends JpaRepository<SystemJhd, Integer> {

    // 根据 business_module, check_code, abnormal_date 查询是否存在
    @Query("SELECT s FROM SystemJhd s WHERE s.businessModule = :businessModule AND s.checkCode = :checkCode AND s.abnormalDate = :abnormalDate")
    Optional<SystemJhd> findByBusinessModuleAndCheckCodeAndAbnormalDate(
        @Param("businessModule") String businessModule,
        @Param("checkCode") String checkCode,
        @Param("abnormalDate") String abnormalDate
    );

    // 查询某日期的所有数据
    List<SystemJhd> findByAbnormalDate(String abnormalDate);
}
