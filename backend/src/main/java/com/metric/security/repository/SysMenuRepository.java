package com.metric.security.repository;

import com.metric.security.entity.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    List<SysMenu> findByStatusOrderBySortOrderAsc(Integer status);

    Optional<SysMenu> findByRoutePath(String routePath);
}
