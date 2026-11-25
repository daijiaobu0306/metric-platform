package com.metric.alarm.service;

import com.metric.alarm.entity.AlarmSwitchConfig;
import com.metric.alarm.repository.AlarmSwitchConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AlarmSwitchService {

    public static final String KEY_ALARM_GLOBAL = "alarm_global_switch";
    public static final String KEY_GONGDAN_GLOBAL = "gongdan_global_switch";
    public static final String KEY_ALARM_EXPRESSION = "alarm_control_expression";
    public static final String KEY_GONGDAN_EXPRESSION = "gongdan_control_expression";

    @Autowired
    private AlarmSwitchConfigRepository repository;

    /**
     * 初始化默认配置
     */
    @jakarta.annotation.PostConstruct
    public void initDefaultConfig() {
        // 初始化告警全局开关
        if (repository.findByConfigKey(KEY_ALARM_GLOBAL).isEmpty()) {
            AlarmSwitchConfig alarmSwitch = new AlarmSwitchConfig();
            alarmSwitch.setConfigKey(KEY_ALARM_GLOBAL);
            alarmSwitch.setConfigValue(1);
            alarmSwitch.setDescription("告警全局开关 - 1启用 0禁用");
            repository.save(alarmSwitch);
        }
        // 初始化工单全局开关
        if (repository.findByConfigKey(KEY_GONGDAN_GLOBAL).isEmpty()) {
            AlarmSwitchConfig gongdanSwitch = new AlarmSwitchConfig();
            gongdanSwitch.setConfigKey(KEY_GONGDAN_GLOBAL);
            gongdanSwitch.setConfigValue(1);
            gongdanSwitch.setDescription("工单全局开关 - 1启用 0禁用");
            repository.save(gongdanSwitch);
        }
    }

    /**
     * 获取告警开关状态
     */
    public boolean isAlarmEnabled() {
        return repository.findByConfigKey(KEY_ALARM_GLOBAL)
                .map(config -> config.getConfigValue() == 1)
                .orElse(true); // 默认启用
    }

    /**
     * 获取工单开关状态
     */
    public boolean isGongdanEnabled() {
        return repository.findByConfigKey(KEY_GONGDAN_GLOBAL)
                .map(config -> config.getConfigValue() == 1)
                .orElse(true); // 默认启用
    }

    /**
     * 设置告警开关
     */
    @Transactional
    public void setAlarmEnabled(boolean enabled) {
        AlarmSwitchConfig config = repository.findByConfigKey(KEY_ALARM_GLOBAL)
                .orElseGet(() -> {
                    AlarmSwitchConfig newConfig = new AlarmSwitchConfig();
                    newConfig.setConfigKey(KEY_ALARM_GLOBAL);
                    newConfig.setDescription("告警全局开关");
                    return newConfig;
                });
        config.setConfigValue(enabled ? 1 : 0);
        repository.save(config);
    }

    /**
     * 设置工单开关
     */
    @Transactional
    public void setGongdanEnabled(boolean enabled) {
        AlarmSwitchConfig config = repository.findByConfigKey(KEY_GONGDAN_GLOBAL)
                .orElseGet(() -> {
                    AlarmSwitchConfig newConfig = new AlarmSwitchConfig();
                    newConfig.setConfigKey(KEY_GONGDAN_GLOBAL);
                    newConfig.setDescription("工单全局开关");
                    return newConfig;
                });
        config.setConfigValue(enabled ? 1 : 0);
        repository.save(config);
    }

    /**
     * 获取控制表达式
     */
    public String getAlarmExpression() {
        return repository.findByConfigKey(KEY_ALARM_EXPRESSION)
                .map(AlarmSwitchConfig::getDescription)
                .orElse(null);
    }

    /**
     * 获取工单控制表达式
     */
    public String getGongdanExpression() {
        return repository.findByConfigKey(KEY_GONGDAN_EXPRESSION)
                .map(AlarmSwitchConfig::getDescription)
                .orElse(null);
    }

    /**
     * 设置控制表达式
     */
    @Transactional
    public void setAlarmExpression(String expression) {
        AlarmSwitchConfig config = repository.findByConfigKey(KEY_ALARM_EXPRESSION)
                .orElseGet(() -> {
                    AlarmSwitchConfig newConfig = new AlarmSwitchConfig();
                    newConfig.setConfigKey(KEY_ALARM_EXPRESSION);
                    return newConfig;
                });
        config.setDescription(expression);
        repository.save(config);
    }

    /**
     * 设置工单控制表达式
     */
    @Transactional
    public void setGongdanExpression(String expression) {
        AlarmSwitchConfig config = repository.findByConfigKey(KEY_GONGDAN_EXPRESSION)
                .orElseGet(() -> {
                    AlarmSwitchConfig newConfig = new AlarmSwitchConfig();
                    newConfig.setConfigKey(KEY_GONGDAN_EXPRESSION);
                    return newConfig;
                });
        config.setDescription(expression);
        repository.save(config);
    }

    /**
     * 获取所有配置
     */
    public List<AlarmSwitchConfig> getAllConfigs() {
        return repository.findAll();
    }

    /**
     * 获取配置Map
     */
    public Map<String, Object> getConfigMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("alarmEnabled", isAlarmEnabled());
        map.put("gongdanEnabled", isGongdanEnabled());
        map.put("alarmExpression", getAlarmExpression());
        map.put("gongdanExpression", getGongdanExpression());
        return map;
    }

    /**
     * 更新配置
     */
    @Transactional
    public AlarmSwitchConfig updateConfig(Long id, Integer configValue, String description) {
        Optional<AlarmSwitchConfig> optConfig = repository.findById(id);
        if (optConfig.isPresent()) {
            AlarmSwitchConfig config = optConfig.get();
            if (configValue != null) {
                config.setConfigValue(configValue);
            }
            if (description != null) {
                config.setDescription(description);
            }
            return repository.save(config);
        }
        return null;
    }

    /**
     * 切换告警开关
     */
    @Transactional
    public boolean toggleAlarm() {
        boolean current = isAlarmEnabled();
        AlarmSwitchConfig config = repository.findByConfigKey(KEY_ALARM_GLOBAL)
                .orElseGet(() -> {
                    AlarmSwitchConfig newConfig = new AlarmSwitchConfig();
                    newConfig.setConfigKey(KEY_ALARM_GLOBAL);
                    newConfig.setDescription("告警全局开关");
                    return newConfig;
                });
        config.setConfigValue(current ? 0 : 1);
        repository.save(config);
        return !current;
    }

    /**
     * 切换工单开关
     */
    @Transactional
    public boolean toggleGongdan() {
        boolean current = isGongdanEnabled();
        AlarmSwitchConfig config = repository.findByConfigKey(KEY_GONGDAN_GLOBAL)
                .orElseGet(() -> {
                    AlarmSwitchConfig newConfig = new AlarmSwitchConfig();
                    newConfig.setConfigKey(KEY_GONGDAN_GLOBAL);
                    newConfig.setDescription("工单全局开关");
                    return newConfig;
                });
        config.setConfigValue(current ? 0 : 1);
        repository.save(config);
        return !current;
    }
}
