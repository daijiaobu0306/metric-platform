-- ============================================================
-- V20260922150000: 修复 V39 seed 的 admin 密码哈希
--
-- 背景（2026-09-22）：
--   V39 默认 seed 的 admin 密码哈希在结构上就不匹配 BCrypt("admin123")：
--     V39 写入: $2y$10$Q.AkQEnTdpVjm2bnKMHv6eNY14m0mOp8BIJ5WqtyBdE51UEmkF8ga
--     BCrypt(admin123) 永远不等于该值 → 所有从空库按 V39 起 seed 的环境
--     第一次登录都会报 "用户名或密码错误"，需要运维按
--     2026-09-09 staging admin BCrypt 重置流程手动 SQL 改一次。
--
-- 为什么不直接改 V39：
--   application.yml 启用了 flyway.validate-on-migrate=true。V39 早已在所有
--   dev/staging/prod 库执行过，修改已应用的 migration 文件会导致下次启动报
--   "Migration checksum mismatch" 直接启动失败，必须跑 flyway repair 才能恢复。
--
-- 做法（与 V55/V56 同思路，DB 端兜底 + 幂等）：
--   只把 sys_user.username='admin' 且 password 仍等于 V39 错误哈希的行
--   改成 BCrypt("admin123") 的正确哈希。已经被运维手动修复过的环境
--   （password 不是 V39 那个错误值）不会被覆盖，幂等安全。
--
-- 新 BCrypt("admin123") 哈希（htpasswd -bnBC 10 "" admin123 生成，strength=10）：
--   $2y$10$wHDIEjDLPmowf2Xwc3hdnelOpCu5vLMRoPW1XLpqPjmyedaH/0QvW
--
-- 适用范围：
--   - 现有 DB：sys_user.password 仍是 V39 错误哈希的 → 修复
--   - 已有正确哈希的本地库（运维手动修过）→ 不变
--   - 全新 DB：V39 先 seed 错误哈希 → 本 migration 紧随其后覆盖 → 最终正确
-- ============================================================

UPDATE sys_user
SET password = '$2y$10$wHDIEjDLPmowf2Xwc3hdnelOpCu5vLMRoPW1XLpqPjmyedaH/0QvW'
WHERE username = 'admin'
  AND password = '$2y$10$Q.AkQEnTdpVjm2bnKMHv6eNY14m0mOp8BIJ5WqtyBdE51UEmkF8ga';