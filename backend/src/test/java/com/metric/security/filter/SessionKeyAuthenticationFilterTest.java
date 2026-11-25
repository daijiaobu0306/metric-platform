package com.metric.security.filter;

import com.metric.config.repository.SystemConfigRepository;
import com.metric.security.entity.SysUser;
import com.metric.security.entity.UserSession;
import com.metric.security.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionKeyAuthenticationFilterTest {
    private final UserSessionRepository sessions = mock(UserSessionRepository.class);
    private final SysUserRepository users = mock(SysUserRepository.class);
    private final SysUserRoleRepository userRoles = mock(SysUserRoleRepository.class);
    private final SysRoleMenuRepository roleMenus = mock(SysRoleMenuRepository.class);
    private final SysRoleRepository roles = mock(SysRoleRepository.class);
    private final SysMenuRepository menus = mock(SysMenuRepository.class);
    private final SystemConfigRepository configs = mock(SystemConfigRepository.class);
    private final SessionKeyAuthenticationFilter filter = new SessionKeyAuthenticationFilter(
            sessions, users, userRoles, roleMenus, roles, menus, configs);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void platformSessionTakesPriorityOverExistingOauthAuthentication() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "oauth-user", "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        UserSession session = new UserSession();
        session.setUsername("admin");
        session.setIsActive(1);
        session.setLastHeartbeat(LocalDateTime.now());
        SysUser admin = new SysUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setIsAdmin(1);
        admin.setStatus(1);
        session.setUserId(1L);
        when(sessions.findBySessionKey("test-key")).thenReturn(Optional.of(session));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(menus.findByStatusOrderBySortOrderAsc(1)).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard-view/workspace/capabilities");
        request.addHeader(SessionKeyAuthenticationFilter.HEADER, "test-key");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertSame(admin, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void invalidPlatformSessionCannotFallBackToOauthCookie() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "oauth-user", "N/A", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(sessions.findBySessionKey("expired-key")).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard-view/workspace/capabilities");
        request.addHeader(SessionKeyAuthenticationFilter.HEADER, "expired-key");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
