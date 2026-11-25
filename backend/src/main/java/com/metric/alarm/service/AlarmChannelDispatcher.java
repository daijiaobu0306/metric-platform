package com.metric.alarm.service;

import com.metric.alarm.entity.Alarm;
import com.metric.alarm.entity.AlarmChannelConfig;
import com.metric.shared.util.PathResolver;
import com.metric.collect.service.PluginExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AlarmChannelDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(AlarmChannelDispatcher.class);

    @Autowired
    private AlarmChannelService alarmChannelService;

    @Autowired
    private PluginExecutorService pluginExecutorService;

    @Autowired
    private PathResolver pathResolver;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FIELD_PATTERN = Pattern.compile("\\{(\\w+)}");

    public void dispatch(Alarm alarm) {
        List<AlarmChannelConfig> enabledChannels = alarmChannelService.findByEnabled();
        for (AlarmChannelConfig channel : enabledChannels) {
            try {
                if ("FILE".equals(channel.getChannelType())) {
                    writeToFile(channel, alarm);
                } else if ("HTTP_API".equals(channel.getChannelType())) {
                    invokePlugin(channel, alarm);
                }
            } catch (Exception e) {
                logger.error("告警通道 [{}] 分发失败: {}", channel.getChannelName(), e.getMessage());
            }
        }
    }

    private void writeToFile(AlarmChannelConfig channel, Alarm alarm) throws Exception {
        String relativePath = channel.getConfigValue();
        if (relativePath == null || relativePath.isEmpty()) {
            logger.warn("文件通道 [{}] 未配置路径", channel.getChannelName());
            return;
        }

        // 使用 PathResolver 转换为完整路径
        String filePath = pathResolver.resolve(relativePath);

        // 确保目录存在：用 Path.getParent() 而不是截取最后一个 '/'，
        // Windows 绝对路径是反斜杠（D:\...），按 '/' 截取会得到 -1 直接抛异常，通道静默失败。
        Path target = Paths.get(filePath).toAbsolutePath();
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }

        String line = buildAlarmLine(channel, alarm);

        try (FileWriter writer = new FileWriter(target.toFile(), true)) {
            writer.write(line + "\n");
        }

        logger.info("告警已写入文件通道 [{}]: {}", channel.getChannelName(), target);
    }

    private String buildAlarmLine(AlarmChannelConfig channel, Alarm alarm) {
        String template = channel.getTemplate();
        if (template == null || template.isEmpty()) {
            return buildDefaultFormat(alarm);
        }
        return parseTemplate(template, alarm);
    }

    private String buildDefaultFormat(Alarm alarm) {
        StringBuilder sb = new StringBuilder();
        sb.append("告警时间: ").append(alarm.getAlarmTime() != null
                ? alarm.getAlarmTime().format(FORMATTER) : LocalDateTime.now().format(FORMATTER));
        sb.append(" | 告警类型: ").append(nullStr(alarm.getAlarmType()));
        sb.append(" | 告警内容: ").append(nullStr(alarm.getAlarmContent()));
        sb.append(" | 指标值: ").append(formatNumber(alarm.getMetricValue()));
        sb.append(" | 阈值: ").append(formatNumber(alarm.getThresholdValue()));
        sb.append(" | 健康度: ").append(nullStr(alarm.getHealthScore()));
        sb.append(" | 大类: ").append(nullStr(alarm.getCategoryName()));
        sb.append(" | 指标: ").append(nullStr(alarm.getMetricName()));
        sb.append(" | 处理状态: ").append(processStatusDesc(alarm.getProcessStatus()));
        sb.append(" | 来源表: ").append(nullStr(alarm.getSourceTable()));
        sb.append(" | 来源ID: ").append(nullStr(alarm.getSourceId()));
        sb.append(" | 系统: ").append(nullStr(alarm.getSystemName()));
        sb.append(" | 小类: ").append(nullStr(alarm.getSubCategoryName()));
        return sb.toString();
    }

    private String parseTemplate(String template, Alarm alarm) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = FIELD_PATTERN.matcher(template);
        while (matcher.find()) {
            String fieldName = matcher.group(1);
            String value = getFieldValue(fieldName, alarm);
            // 空值不显示该字段
            if (value == null || value.isEmpty() || "/".equals(value)) {
                matcher.appendReplacement(sb, "");
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(sb);
        // 清理多余的空格和标点
        return cleanTemplateOutput(sb.toString());
    }

    private String cleanTemplateOutput(String output) {
        // 移除重复的逗号
        output = output.replaceAll("[，,]+", ",");
        // 移除多余空格
        output = output.replaceAll("\\s+", " ");
        // 移除开头多余的逗号或空格
        output = output.replaceAll("^[，,\\s]+", "");
        // 移除结尾多余的逗号
        output = output.replaceAll("[，,]+$", "");
        return output.trim();
    }

    private String getFieldValue(String fieldName, Alarm alarm) {
        switch (fieldName) {
            case "alarmTime": return alarm.getAlarmTime() != null ? alarm.getAlarmTime().format(FORMATTER) : "/";
            case "alarmType": return nullStr(alarm.getAlarmType());
            case "alarmContent": return nullStr(alarm.getAlarmContent());
            case "metricValue": return formatNumber(alarm.getMetricValue());
            case "thresholdValue": return formatNumber(alarm.getThresholdValue());
            case "healthScore": return nullStr(alarm.getHealthScore());
            case "categoryName": return nullStr(alarm.getCategoryName());
            case "metricName": return nullStr(alarm.getMetricName());
            case "processStatus": return processStatusDesc(alarm.getProcessStatus());
            case "sourceTable": return nullStr(alarm.getSourceTable());
            case "sourceId": return nullStr(alarm.getSourceId());
            case "systemName": return nullStr(alarm.getSystemName());
            case "subCategoryName": return nullStr(alarm.getSubCategoryName());
            default: return "{" + fieldName + "}";
        }
    }

    private String formatNumber(BigDecimal value) {
        if (value == null) return "/";
        return value.stripTrailingZeros().toPlainString();
    }

    private String nullStr(Object obj) {
        if (obj == null) return "/";
        if (obj instanceof BigDecimal) {
            return formatNumber((BigDecimal) obj);
        }
        String s = obj.toString();
        return s.isEmpty() ? "/" : s;
    }

    private String processStatusDesc(Integer status) {
        if (status == null) return "/";
        switch (status) {
            case 0: return "未处理";
            case 1: return "已处理";
            case 2: return "已关单";
            case 3: return "工单";
            default: return "未知(" + status + ")";
        }
    }

    private void invokePlugin(AlarmChannelConfig channel, Alarm alarm) {
        String pluginIdStr = channel.getConfigValue();
        if (pluginIdStr == null || pluginIdStr.isEmpty()) {
            logger.warn("HTTP通道 [{}] 未配置插件ID", channel.getChannelName());
            return;
        }

        Long pluginId;
        try {
            pluginId = Long.parseLong(pluginIdStr);
        } catch (NumberFormatException e) {
            logger.error("HTTP通道 [{}] 插件ID格式错误: {}", channel.getChannelName(), pluginIdStr);
            return;
        }

        Map<String, Object> customParams = new HashMap<>();
        customParams.put("alarm", alarm);
        customParams.put("alarmId", alarm.getId());
        customParams.put("alarmType", alarm.getAlarmType());
        customParams.put("alarmContent", alarm.getAlarmContent());
        customParams.put("alarmTime", alarm.getAlarmTime());
        customParams.put("metricValue", alarm.getMetricValue());
        customParams.put("categoryName", alarm.getCategoryName());
        customParams.put("metricName", alarm.getMetricName());

        pluginExecutorService.execute(pluginId, "alarm", alarm.getId(), customParams);
        logger.info("告警已触发HTTP通道 [{}], 插件ID: {}", channel.getChannelName(), pluginId);
    }
}
