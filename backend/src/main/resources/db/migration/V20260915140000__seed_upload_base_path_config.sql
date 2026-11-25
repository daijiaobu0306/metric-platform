-- V20260915140000: 幂等补种 system_config['upload.base_path'] 行
--
-- 背景：原 /app/files 是 @Value 启动期绑定，容器/本机路径不一致时改 application.yml
--       必须重启。改成 system_config 运行时配置后，由 BasePathProvider 优先读 DB、
--       回退到 yml 默认值。
--
-- 为什么 seed 空值而非默认值：
--   - 所有环境（容器 / 本机 / staging）的默认值不同（容器 /app/files，本机 ~/metric_code/files）
--   - DB 里只放「用户明确改过的值」；空字符串走 BasePathProvider 的 yml 兜底
--   - 这样 staging dump 后 baseline 也不会因为这个 key 把路径锁死
--
-- ON DUPLICATE KEY UPDATE id = id：纯 no-op，已有值的环境不会被覆盖。
-- 已有值的 staging 库（如果手填过）保持不动，未填的环境补上这一行。
--
-- 为什么用日期前缀而非 V58：DB 已有 V20260914130000（version 数字远大于 58），
--   Flyway 9.x 不允许 resolved migration 序号小于已应用的 timestamp migration（"Detected
--   resolved migration not applied to database: 58"）。改用 V20260915140000 即可。

INSERT INTO system_config (config_key, config_value, config_type, description, status, create_time, update_time) VALUES
('upload.base_path', '', 'upload', '导入文件根目录（运行时可改，无需重启；容器默认 /app/files，本机默认 ~/metric_code/files）', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE id = id;
