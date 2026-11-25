-- V20261116180000: 分享链接密钥密文列
-- 背景：dashboard_share_link 过去只保存 secret 的 SHA-256 哈希，管理员关闭“生成链接”弹窗后无法再取回完整 URL。
-- 处理：新增一列保存 AES-GCM 密文，仅管理员列表接口解密回显；secret_hash 继续用于访客校验，逻辑不变。
-- 兼容：历史链接该列为 NULL，列表会标记为“不可恢复”，需重新生成链接。
ALTER TABLE dashboard_share_link
    ADD COLUMN secret_cipher VARCHAR(255) NULL COMMENT '分享密钥密文（AES-GCM，供管理员历史回显）' AFTER secret_hash;
