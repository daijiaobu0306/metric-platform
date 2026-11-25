-- V57: 撤掉 V56 里两行凭据形状的 placeholder 种子
--
-- 背景：V56 幂等补种 system_config 时把 sso.client_id / sso.client_secret 也种了
--       'placeholder-client-id' / 'placeholder-client-secret'。
--       但这两个值在运行期由 application.yml / 环境变量绑定，DB 里的行只用于配置页只读展示，
--       种 placeholder 反而会在 UI 上显示成"已配置"，误导排查（2026-09-09 已踩过同款坑）。
--
-- 做法：只删除「值仍然等于 placeholder」的行；任何环境里已被填成真实凭据的行不受影响。
--       行不存在时配置页会走 catch 兜底（前端已改），不再报 Config not found。

DELETE FROM system_config
 WHERE config_key = 'sso.client_id'
   AND config_value = 'placeholder-client-id';

DELETE FROM system_config
 WHERE config_key = 'sso.client_secret'
   AND config_value = 'placeholder-client-secret';
