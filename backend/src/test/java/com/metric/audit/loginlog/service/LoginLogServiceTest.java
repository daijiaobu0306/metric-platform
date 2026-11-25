package com.metric.audit.loginlog.service;

import com.metric.audit.loginlog.entity.LoginLog;
import com.metric.audit.loginlog.repository.LoginLogRepository;
import com.metric.security.entity.SysUser;
import com.metric.security.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LoginLogService 单测 — 覆盖 4 个写入路径 + 1 个查询路径：
 *   1) recordLocalLoginSuccess: status=ONLINE, source=LOCAL, result=SUCCESS
 *   2) recordLocalLoginNotFound: status=OFFLINE, logoutTime=loginTime, failReason=用户不存在
 *   3) recordLocalLoginBadPassword: status=ONLINE (因为 user 已查到), failReason=密码错误
 *   4) recordLogout: 找到 ONLINE 行 → 改 OFFLINE + 写 durationMinutes
 *   5) recordLogout: 找不到 → 不写
 */
@ExtendWith(MockitoExtension.class)
class LoginLogServiceTest {

    @Mock
    private LoginLogRepository repo;

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private LoginLogService service;

    // =================================================================
    // 1. 本地登录成功
    // =================================================================
    @Test
    void recordLocalLoginSuccessWritesOnlineRow() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setUsername("alice");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.1.2.3");
        req.addHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15");

        service.recordLocalLoginSuccess(user, req);

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(repo, times(1)).save(captor.capture());
        LoginLog row = captor.getValue();
        assertEquals("alice", row.getUsername());
        assertEquals(7L, row.getUserId());
        assertEquals(LoginLogService.SOURCE_LOCAL, row.getSource());
        assertEquals(LoginLogService.RESULT_SUCCESS, row.getResult());
        assertEquals(LoginLogService.STATUS_ONLINE, row.getStatus());
        assertEquals("10.1.2.3", row.getClientIp());
        assertNotNull(row.getLoginTime());
        // ONLINE 状态下 logoutTime 仍为 null（duration_minutes 保持 NULL）
        assertNull(row.getLogoutTime());
    }

    // =================================================================
    // 2. 本地登录用户不存在
    // =================================================================
    @Test
    void recordLocalLoginNotFoundWritesOfflineRowWithFailReason() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.168.0.1");

        service.recordLocalLoginNotFound("ghost", req);

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(repo, times(1)).save(captor.capture());
        LoginLog row = captor.getValue();
        assertEquals("ghost", row.getUsername());
        assertEquals(LoginLogService.SOURCE_LOCAL, row.getSource());
        assertEquals(LoginLogService.RESULT_FAILED, row.getResult());
        assertEquals(LoginLogService.STATUS_OFFLINE, row.getStatus());
        assertEquals("用户不存在", row.getFailReason());
        // logoutTime 强制等于 loginTime → duration_minutes 保持 NULL
        assertNotNull(row.getLoginTime());
        assertEquals(row.getLoginTime(), row.getLogoutTime());
        assertNull(row.getDurationMinutes());
    }

    // =================================================================
    // 3. 本地登录密码错（用户已查到 → status=ONLINE 等待后续 timeout 扫描）
    // =================================================================
    @Test
    void recordLocalLoginBadPasswordWritesOnlineRowWithFailReason() {
        SysUser user = new SysUser();
        user.setId(8L);
        user.setUsername("bob");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");

        service.recordLocalLoginBadPassword(user, req);

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(repo, times(1)).save(captor.capture());
        LoginLog row = captor.getValue();
        assertEquals("bob", row.getUsername());
        assertEquals(8L, row.getUserId());
        assertEquals(LoginLogService.RESULT_FAILED, row.getResult());
        // 用户已查到 → 状态置 ONLINE（由后续 timeout 扫描兜底）
        assertEquals(LoginLogService.STATUS_ONLINE, row.getStatus());
        assertEquals("密码错误", row.getFailReason());
    }

    // =================================================================
    // 4. recordLogout: 找到 ONLINE 行 → 标 OFFLINE + 写 durationMinutes
    // =================================================================
    @Test
    void recordLogoutFlipsOnlineRowToOffline() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("carol");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.9.9.9");

        LoginLog existing = new LoginLog();
        existing.setId(100L);
        existing.setUsername("carol");
        existing.setUserId(9L);
        existing.setSource(LoginLogService.SOURCE_LOCAL);
        existing.setResult(LoginLogService.RESULT_SUCCESS);
        existing.setStatus(LoginLogService.STATUS_ONLINE);
        // 5 分钟前登录
        existing.setLoginTime(java.time.LocalDateTime.now().minusMinutes(5));
        when(repo.findFirstByUsernameAndStatusOrderByLoginTimeDesc("carol", LoginLogService.STATUS_ONLINE))
                .thenReturn(Optional.of(existing));

        service.recordLogout("carol", req);

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(repo, times(1)).save(captor.capture());
        LoginLog saved = captor.getValue();
        assertEquals(LoginLogService.STATUS_OFFLINE, saved.getStatus());
        assertNotNull(saved.getLogoutTime());
        // durationMinutes: 从 loginTime 到现在
        assertNotNull(saved.getDurationMinutes());
        assertTrue(saved.getDurationMinutes() >= 5);
    }

    // =================================================================
    // 5. recordLogout: 找不到 → 不写
    // =================================================================
    @Test
    void recordLogoutNoOpWhenNoOnlineRowExists() {
        when(repo.findFirstByUsernameAndStatusOrderByLoginTimeDesc("dave", LoginLogService.STATUS_ONLINE))
                .thenReturn(Optional.empty());
        MockHttpServletRequest req = new MockHttpServletRequest();

        service.recordLogout("dave", req);

        verify(repo, never()).save(any());
    }

    // =================================================================
    // 6. 写入失败时不能抛 — 内部 try/catch + log.warn
    // =================================================================
    @Test
    void recordLocalLoginSuccessSwallowsRepositoryException() {
        SysUser user = new SysUser();
        user.setId(10L);
        user.setUsername("eve");
        MockHttpServletRequest req = new MockHttpServletRequest();
        doThrow(new RuntimeException("db down")).when(repo).save(any(LoginLog.class));

        // 不应抛
        assertDoesNotThrow(() -> service.recordLocalLoginSuccess(user, req));
    }
}
