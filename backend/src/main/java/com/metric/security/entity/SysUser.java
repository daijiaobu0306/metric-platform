package com.metric.security.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SelectBeforeUpdate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sys_user")
@DynamicUpdate
@SelectBeforeUpdate
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password", nullable = false, length = 100)
    private String password;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "status", columnDefinition = "TINYINT")
    private Integer status;

    @Column(name = "is_admin", columnDefinition = "TINYINT")
    private Integer isAdmin;

    // ===== V42 OIDC SSO =====
    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "source", length = 50)
    private String source;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
        if (status == null) {
            status = 1;
        }
        if (isAdmin == null) {
            isAdmin = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}
