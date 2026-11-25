package com.metric.metric.repository;

import com.metric.metric.entity.SystemJhdDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface SystemJhdDetailRepository extends JpaRepository<SystemJhdDetail, Integer> {

    // 根据 wsId (工单编码) 和 auditId (稽核点编号) 查询是否存在
    @Query("SELECT s FROM SystemJhdDetail s WHERE s.wsId = :wsId AND s.auditId = :auditId")
    Optional<SystemJhdDetail> findByWsIdAndAuditId(
        @Param("wsId") String wsId,
        @Param("auditId") String auditId
    );

    // 查询某工单月份的所有数据
    List<SystemJhdDetail> findByRsvrStr1(String rsvrStr1);

    // 根据部门查询
    List<SystemJhdDetail> findByDepartment(String department);
}