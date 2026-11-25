-- 将会话绑定到不可变的本地用户 ID，避免按显示名反查产生身份混淆。
ALTER TABLE user_session ADD COLUMN user_id BIGINT NULL COMMENT '登录的本地用户ID';
CREATE INDEX idx_user_session_user_id ON user_session (user_id);

-- 旧会话没有可靠的用户 ID，升级后要求重新登录。
UPDATE user_session SET is_active = 0, logout_time = NOW()
WHERE user_id IS NULL AND is_active = 1;
