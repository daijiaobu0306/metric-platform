ALTER TABLE alarm ADD COLUMN shield_unshield_time DATETIME COMMENT '屏蔽解除时间' AFTER shield_time;
ALTER TABLE alarm ADD COLUMN shield_unshield_type VARCHAR(20) COMMENT '解除方式: AUTO(到期自动), MANUAL(手工解除)' AFTER shield_unshield_time;
