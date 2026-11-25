-- V47: P5/2026-07 重构——Bearer JWT 替代 user_session 表
--
-- 历史：
--   V35 创建 user_session（本地登录会话表）
--   V42 加 source 列
--   2026-07 P5 重构：鉴权从 X-Session-Key 切到后端自签 HS256 JWT，user_session 不再被读写
--
-- 本次直接 DROP TABLE。session 是临时态（无需审计保留），不存在数据迁移问题。
-- 前端 V1 改用 localStorage.accessToken；后端 JwtAuthenticationFilter 从 Authorization header 取 Bearer token。

DROP TABLE IF EXISTS user_session;
