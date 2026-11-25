-- 扩展 alarm 表中 category_name 字段长度，从 50 扩展到 200
-- 原因：告警策略生成的 category_name 可能超过 50 字符导致插入失败
ALTER TABLE alarm MODIFY COLUMN category_name VARCHAR(200);
