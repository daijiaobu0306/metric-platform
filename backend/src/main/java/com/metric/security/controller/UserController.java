package com.metric.security.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.security.entity.SysMenu;
import com.metric.security.entity.SysRole;
import com.metric.security.entity.SysRoleMenu;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.SysUserRole;
import com.metric.security.repository.*;
import com.metric.security.service.CurrentUserProvider;
import com.metric.security.service.SessionService;
import com.metric.observe.service.DashboardGrantReferenceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private SysUserRepository userRepository;

    @Autowired
    private SysUserRoleRepository userRoleRepository;

    @Autowired
    private SysRoleRepository roleRepository;

    @Autowired
    private SysRoleMenuRepository roleMenuRepository;

    @Autowired
    private SysMenuRepository menuRepository;

    @Autowired private CurrentUserProvider currentUserProvider;
    @Autowired private DashboardGrantReferenceService dashboardReferences;
    @Autowired private SessionService sessionService;

    private void requireAdmin() {
        if (currentUserProvider.currentUser().map(u -> Integer.valueOf(1).equals(u.getIsAdmin())).orElse(false)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可管理用户");
    }

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @GetMapping
    public ApiResponse<Page<SysUser>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) Integer status) {
        requireAdmin();

        Specification<SysUser> spec = Specification.where(null);
        if (username != null && !username.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.like(root.get("username"), "%" + username + "%"));
        }
        if (displayName != null && !displayName.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.like(root.get("displayName"), "%" + displayName + "%"));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createTime"));
        Page<SysUser> result = userRepository.findAll(spec, pageable);
        // 清除密码字段
        result.getContent().forEach(u -> u.setPassword(null));
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getUserById(@PathVariable Long id) {
        requireAdmin();
        return userRepository.findById(id)
                .map(user -> {
                    user.setPassword(null);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("user", user);
                    List<Long> roleIds = userRoleRepository.findByUserId(id)
                            .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
                    result.put("roleIds", roleIds);
                    List<SysRole> roles = roleRepository.findAllById(roleIds);
                    result.put("roles", roles);
                    return ApiResponse.success(result);
                })
                .orElse(ApiResponse.error("用户不存在"));
    }

    @PostMapping
    @Transactional
    public ApiResponse<SysUser> createUser(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String displayName = (String) body.get("displayName");
        Integer isAdmin = parseFlag(body.get("isAdmin"));
        if (isAdmin == null) isAdmin = 0;

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.error("用户名和密码不能为空");
        }

        Optional<SysUser> existing = userRepository.findByUsername(username.trim());
        if (existing.isPresent()) {
            return ApiResponse.error("用户名已存在");
        }

        @SuppressWarnings("unchecked")
        List<Number> roleIds = (List<Number>) body.get("roleIds");
        // 未选角色时兜底「普通用户」：null 和空数组都算，避免建出无任何角色的账号，
        // 登录后菜单全空、前端守卫无落脚点。
        if (roleIds == null || roleIds.isEmpty()) roleIds = List.of(sessionService.resolveNormalUserRoleId());
        if (roleIds.stream().anyMatch(Objects::isNull)) return ApiResponse.error("角色无效");
        List<Long> selectedRoleIds = roleIds.stream().map(Number::longValue).toList();
        if (selectedRoleIds.size() != new HashSet<>(selectedRoleIds).size()
                || !roleRepository.findAllById(selectedRoleIds).stream().map(SysRole::getId)
                    .collect(Collectors.toSet()).containsAll(selectedRoleIds))
            return ApiResponse.error("角色不存在或重复");

        SysUser user = new SysUser();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(password));
        user.setDisplayName(displayName != null ? displayName.trim() : username.trim());
        user.setIsAdmin(isAdmin);
        SysUser saved = userRepository.save(user);

        // 分配角色
        for (Number roleId : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(saved.getId());
            ur.setRoleId(roleId.longValue());
            userRoleRepository.save(ur);
        }

        // 必须复制一份再脱敏，否则在事务内修改 managed 实体的 password 字段
        // 会触发 Hibernate 脏检查，commit 时执行 UPDATE 把 password 改成 null，
        // 违反 sys_user.password NOT NULL 约束
        SysUser response = new SysUser();
        response.setId(saved.getId());
        response.setUsername(saved.getUsername());
        response.setDisplayName(saved.getDisplayName());
        response.setStatus(saved.getStatus());
        response.setIsAdmin(saved.getIsAdmin());
        response.setCreateTime(saved.getCreateTime());
        response.setUpdateTime(saved.getUpdateTime());
        return ApiResponse.success(response);
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<SysUser> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        return userRepository.findById(id)
                .map(existing -> {
                    if (body.containsKey("displayName")) {
                        existing.setDisplayName((String) body.get("displayName"));
                    }
                    if (body.containsKey("status")) {
                        Integer v = parseFlag(body.get("status"));
                        if (v != null) existing.setStatus(v);
                    }
                    if (body.containsKey("isAdmin")) {
                        Integer v = parseFlag(body.get("isAdmin"));
                        if (v != null) existing.setIsAdmin(v);
                    }
                    SysUser saved = userRepository.save(existing);
                    // 必须复制一份再脱敏：直接 saved.setPassword(null) 会修改事务内的 managed 实体，
                    // 脏检查在 commit 时执行 UPDATE sys_user SET password=NULL，
                    // 违反 password NOT NULL 约束，导致「编辑保存」必报 500。
                    SysUser response = new SysUser();
                    response.setId(saved.getId());
                    response.setUsername(saved.getUsername());
                    response.setDisplayName(saved.getDisplayName());
                    response.setStatus(saved.getStatus());
                    response.setIsAdmin(saved.getIsAdmin());
                    response.setExternalId(saved.getExternalId());
                    response.setSource(saved.getSource());
                    response.setEmail(saved.getEmail());
                    response.setLastLoginTime(saved.getLastLoginTime());
                    response.setCreateTime(saved.getCreateTime());
                    response.setUpdateTime(saved.getUpdateTime());
                    return ApiResponse.success(response);
                })
                .orElse(ApiResponse.error("用户不存在"));
    }

    /**
     * 兼容前端可能传来的 1/0、true/false、"true"/"false"。
     * 旧实现直接强转 Number，布尔值会抛 ClassCastException 变成 500。
     */
    private Integer parseFlag(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b ? 1 : 0;
        if (value instanceof Number n) return n.intValue();
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        if ("true".equalsIgnoreCase(s)) return 1;
        if ("false".equalsIgnoreCase(s)) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        requireAdmin();
        if (id == 1L) {
            return ApiResponse.error("默认管理员不允许删除");
        }
        if (dashboardReferences.references("user", id)) return ApiResponse.error("此用户仍被专屏授权或主管关联引用，请先移除授权");
        userRepository.deleteById(id);
        userRoleRepository.deleteByUserId(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/password")
    public ApiResponse<Void> changePassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        requireAdmin();
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");

        if (newPassword == null || newPassword.isBlank()) {
            return ApiResponse.error("新密码不能为空");
        }

        return userRepository.findById(id)
                .map(user -> {
                    // 管理员可以不验证旧密码直接修改
                    if (oldPassword != null && !oldPassword.isBlank()) {
                        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
                            return ApiResponse.<Void>error("旧密码错误");
                        }
                    }
                    user.setPassword(passwordEncoder.encode(newPassword));
                    userRepository.save(user);
                    return ApiResponse.<Void>success();
                })
                .orElse(ApiResponse.error("用户不存在"));
    }

    @PutMapping("/{id}/roles")
    @Transactional
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        requireAdmin();
        if (!userRepository.existsById(id)) {
            return ApiResponse.error("用户不存在");
        }
        if (roleIds == null || roleIds.contains(null) || roleIds.size() != new HashSet<>(roleIds).size()
                || !roleRepository.findAllById(roleIds).stream().map(SysRole::getId).collect(Collectors.toSet()).containsAll(roleIds))
            return ApiResponse.error("角色不存在或重复");
        userRoleRepository.deleteByUserId(id);
        for (Long roleId : roleIds) {
            SysUserRole ur = new SysUserRole();
            ur.setUserId(id);
            ur.setRoleId(roleId);
            userRoleRepository.save(ur);
        }
        return ApiResponse.success();
    }

    @GetMapping("/current")
    public ApiResponse<Map<String, Object>> getCurrentUser(
            @RequestParam(required = false) String sessionKey,
            @RequestParam(required = false) String username) {

        SysUser caller = currentUserProvider.currentUser().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
        if (!Integer.valueOf(1).equals(caller.getIsAdmin()) && !caller.getUsername().equals(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能查看本人信息");

        // 通过 sessionKey 或 username 查找用户
        SysUser user = null;
        if (username != null && !username.isBlank()) {
            Optional<SysUser> opt = userRepository.findByUsername(username.trim());
            if (opt.isPresent()) {
                user = opt.get();
            }
        }

        if (user == null) {
            return ApiResponse.error("未找到用户信息");
        }

        user.setPassword(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", user);

        // 获取角色
        List<Long> roleIds = userRoleRepository.findByUserId(user.getId())
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        result.put("roleIds", roleIds);
        List<SysRole> roles = roleRepository.findAllById(roleIds);
        result.put("roles", roles);

        result.put("permissions", sessionService.computePermissions(user));

        return ApiResponse.success(result);
    }
}
