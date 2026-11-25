package com.metric.security.repository;

import com.metric.security.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long>, JpaSpecificationExecutor<SysUser> {

    Optional<SysUser> findByUsername(String username);

    Optional<SysUser> findByUsernameAndStatus(String username, Integer status);

    /**
     * 用显示名查找（user_session 表的 username 字段实际存的就是 displayName）。
     * 给 init/SSO 流程用：通过 session.username 反查 sys_user 以计算完整 profile。
     */
    Optional<SysUser> findByDisplayName(String displayName);

    // ===== V42 OIDC SSO: 通过 IdP sub 查找本地用户 =====
    Optional<SysUser> findByExternalId(String externalId);
}
