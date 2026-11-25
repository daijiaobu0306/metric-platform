package com.metric.security.repository;

import com.metric.security.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    Optional<SysRole> findByRoleName(String roleName);

    List<SysRole> findByStatus(Integer status);

    // ===== V42 OIDC SSO: 通过 IdP 角色 ID 查找本地角色 =====
    Optional<SysRole> findByExternalId(String externalId);
}
