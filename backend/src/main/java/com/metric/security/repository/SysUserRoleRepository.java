package com.metric.security.repository;

import com.metric.security.entity.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SysUserRoleRepository extends JpaRepository<SysUserRole, Long> {

    List<SysUserRole> findByUserId(Long userId);

    List<SysUserRole> findByRoleId(Long roleId);

    void deleteByRoleId(Long roleId);

    /**
     * 用 bulk delete 立即落库。
     * 派生删除只会把 DELETE 排进 Hibernate action queue，而 action queue 执行顺序是
     * insert → update → delete；「先删后插」重新分配角色时，新行会先插、旧行后删，
     * 撞上 uk_user_role 唯一键报 Duplicate entry。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SysUserRole ur WHERE ur.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
