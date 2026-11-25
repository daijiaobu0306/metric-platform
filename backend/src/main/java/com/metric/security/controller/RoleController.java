package com.metric.security.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.security.entity.SysRole;
import com.metric.security.entity.SysRoleMenu;
import com.metric.security.repository.SysRoleMenuRepository;
import com.metric.security.repository.SysRoleRepository;
import com.metric.security.repository.SysMenuRepository;
import com.metric.security.entity.SysMenu;
import com.metric.security.repository.SysUserRepository;
import com.metric.security.repository.SysUserRoleRepository;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.SysUserRole;
import com.metric.security.service.CurrentUserProvider;
import com.metric.observe.service.DashboardGrantReferenceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/role")
public class RoleController {

    @Autowired
    private SysRoleRepository roleRepository;

    @Autowired
    private SysRoleMenuRepository roleMenuRepository;

    @Autowired private SysUserRoleRepository userRoleRepository;
    @Autowired private SysMenuRepository menuRepository;
    @Autowired private SysUserRepository userRepository;
    @Autowired private CurrentUserProvider currentUserProvider;
    @Autowired private DashboardGrantReferenceService dashboardReferences;

    private void requireAdmin() {
        if (currentUserProvider.currentUser().map(u -> Integer.valueOf(1).equals(u.getIsAdmin())).orElse(false)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可管理角色");
    }

    @GetMapping
    public ApiResponse<List<SysRole>> getAllRoles() {
        requireAdmin();
        return ApiResponse.success(roleRepository.findAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getRoleById(@PathVariable Long id) {
        requireAdmin();
        return roleRepository.findById(id)
                .map(role -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("role", role);
                    List<Long> menuIds = roleMenuRepository.findByRoleId(id)
                            .stream().map(SysRoleMenu::getMenuId).collect(Collectors.toList());
                    result.put("menuIds", menuIds);
                    result.put("userIds", userRoleRepository.findByRoleId(id).stream()
                            .map(SysUserRole::getUserId).toList());
                    return ApiResponse.success(result);
                })
                .orElse(ApiResponse.error("角色不存在"));
    }

    @PostMapping
    public ApiResponse<SysRole> createRole(@RequestBody SysRole role) {
        requireAdmin();
        if (role.getRoleName() == null || role.getRoleName().isBlank()) {
            return ApiResponse.error("角色名称不能为空");
        }
        Optional<SysRole> existing = roleRepository.findByRoleName(role.getRoleName().trim());
        if (existing.isPresent()) {
            return ApiResponse.error("角色名称已存在");
        }
        role.setRoleName(role.getRoleName().trim());
        return ApiResponse.success(roleRepository.save(role));
    }

    @PutMapping("/{id}")
    public ApiResponse<SysRole> updateRole(@PathVariable Long id, @RequestBody SysRole role) {
        requireAdmin();
        return roleRepository.findById(id)
                .map(existing -> {
                    if ("普通用户".equals(existing.getRoleName()) && (role.getStatus() != null && role.getStatus() != 1
                            || role.getRoleName() != null && !"普通用户".equals(role.getRoleName().trim())))
                        return ApiResponse.<SysRole>error("基础角色不能停用或改名");
                    if (role.getRoleName() != null && !role.getRoleName().isBlank()) {
                        // 检查名称是否与其他角色冲突
                        Optional<SysRole> conflict = roleRepository.findByRoleName(role.getRoleName().trim());
                        if (conflict.isPresent() && !conflict.get().getId().equals(id)) {
                            return ApiResponse.<SysRole>error("角色名称已存在");
                        }
                        existing.setRoleName(role.getRoleName().trim());
                    }
                    if (role.getDescription() != null) {
                        existing.setDescription(role.getDescription());
                    }
                    if (role.getStatus() != null) {
                        existing.setStatus(role.getStatus());
                    }
                    return ApiResponse.success(roleRepository.save(existing));
                })
                .orElse(ApiResponse.error("角色不存在"));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> deleteRole(@PathVariable Long id) {
        requireAdmin();
        if (roleRepository.findById(id).map(r -> "普通用户".equals(r.getRoleName())).orElse(false)) {
            return ApiResponse.error("默认角色不允许删除");
        }
        if (dashboardReferences.references("role", id)) return ApiResponse.error("此角色仍被专屏授权引用，请先移除授权");
        roleRepository.deleteById(id);
        roleMenuRepository.deleteByRoleId(id);
        userRoleRepository.deleteByRoleId(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/menus")
    @Transactional
    public ApiResponse<Void> assignMenus(@PathVariable Long id, @RequestBody List<Long> menuIds) {
        requireAdmin();
        if (!roleRepository.existsById(id)) {
            return ApiResponse.error("角色不存在");
        }
        if (menuIds == null || menuIds.contains(null) || menuIds.size() != Set.copyOf(menuIds).size())
            return ApiResponse.error("菜单重复或无效");
        if (roleRepository.findById(id).map(r -> "普通用户".equals(r.getRoleName())).orElse(false))
            return ApiResponse.error("基础角色菜单固定为运营观测，请配置其他角色");
        if (!menuRepository.findAllById(menuIds).stream().map(SysMenu::getId).collect(Collectors.toSet()).containsAll(menuIds))
            return ApiResponse.error("菜单不存在");
        roleMenuRepository.deleteByRoleId(id);
        for (Long menuId : menuIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setRoleId(id);
            rm.setMenuId(menuId);
            roleMenuRepository.save(rm);
        }
        return ApiResponse.success();
    }

    @PutMapping("/{id}/users")
    @Transactional
    public ApiResponse<Void> assignUsers(@PathVariable Long id, @RequestBody List<Long> userIds) {
        requireAdmin();
        if (!roleRepository.existsById(id)) return ApiResponse.error("角色不存在");
        if (userIds == null || userIds.contains(null) || userIds.size() != Set.copyOf(userIds).size()
                || !userRepository.findAllById(userIds).stream().map(SysUser::getId).collect(Collectors.toSet()).containsAll(userIds))
            return ApiResponse.error("用户不存在或重复");
        userRoleRepository.deleteByRoleId(id);
        for (Long userId : userIds) {
            SysUserRole relation = new SysUserRole();
            relation.setRoleId(id);
            relation.setUserId(userId);
            userRoleRepository.save(relation);
        }
        return ApiResponse.success();
    }
}
