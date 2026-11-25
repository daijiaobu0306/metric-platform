package com.metric.gongdan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.gongdan.dto.BomcTaskResult;
import com.metric.gongdan.dto.BomcTaskTrace;
import com.metric.gongdan.entity.Gongdan;
import com.metric.config.entity.SystemConfig;
import com.metric.metric.entity.SystemInfo;
import com.metric.config.repository.SystemConfigRepository;
import com.metric.metric.repository.SystemInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 省 BOMC 端到端告警分析工单接口客户端。
 *
 * <p>依据《省BOMC-端到端-告警分析工单接口规范 V1.0》与 2026-10-09 生产实测结果：
 * 接口路径、请求头、参数名、判定规则全部<b>写死在本类中</b>，不再依赖"插件配置"。
 * 只有"环境相关"的值（地址、操作人、事件来源等）允许通过 system_config 覆盖，
 * 便于内网联调 / 生产环境调整时不必重新打包。
 *
 * <p>实测确认：调用这两个接口<b>无需鉴权令牌</b>，仅需
 * {@code Content-Type: application/json; charset=utf-8} 与 {@code Accept-Charset: utf-8}。
 */
@Service
public class BomcGongdanClient {

    @Value("${bomc.enabled:true}")
    private boolean enabled = true;

    private static final Logger log = LoggerFactory.getLogger(BomcGongdanClient.class);

    /** 创建工单（接口规范 4.2） */
    private static final String PATH_CREATE_TASK = "/rest/itsm/v7/wgt/createTask";
    /** 透明化查询（接口规范 4.7） */
    private static final String PATH_QUERY_TASK = "/rest/itsm/v7/wgt/queryTask";
    /** 归档工单（2026-10-16 生产实测：未回复完会 status=fail） */
    private static final String PATH_ARCHIVE_TASK = "/rest/itsm/v7/wgt/archiveTask";
    /** 撤销工单（2026-10-16 生产实测通过） */
    private static final String PATH_QUASH_TASK = "/rest/itsm/v7/wgt/quashTask";

    /** 生产地址（接口规范 4.1 + 2026-10-09 生产实测） */
    private static final String DEFAULT_BASE_URL = "http://192.168.1.16:80";

    // ---------- system_config 配置键（可覆盖，不配则用下面的默认值）----------
    private static final String KEY_BASE_URL = "bomc.base.url";
    private static final String KEY_OPERATOR_PERSON = "bomc.operator.person";
    private static final String KEY_OPERATOR_NO = "bomc.operator.no";
    private static final String KEY_OPERATOR_DEPART = "bomc.operator.depart";
    private static final String KEY_OPERATOR_CONTACT = "bomc.operator.contact";
    private static final String KEY_EXECUTE_USER = "bomc.execute.user";
    private static final String KEY_RESPONSIBLE = "bomc.responsible.person";
    private static final String KEY_SECOND_RESPONSIBLE = "bomc.second.responsible";
    private static final String KEY_DEALED_IDEA = "bomc.task.dealed.idea";
    private static final String KEY_EVENT_FROM = "bomc.mat.from";
    private static final String KEY_MO_TYPE = "bomc.mo.type";
    private static final String KEY_SYSTEM_TYPE = "bomc.system.type";

    /**
     * 界面（工单配置页）可直接双击修改的字段 → system_config 配置键。
     * 不在此白名单内的配置键只能通过配置管理维护，避免界面上误改内部参数。
     */
    private static final Map<String, String> EDITABLE_CONFIG_KEYS = Map.of(
            "baseUrl", KEY_BASE_URL,
            "opPerson", KEY_OPERATOR_PERSON,
            "opPersonNo", KEY_OPERATOR_NO,
            "opDepart", KEY_OPERATOR_DEPART,
            "opContact", KEY_OPERATOR_CONTACT,
            "executeUser", KEY_EXECUTE_USER
    );

    // ---------- 接口固定值（接口规范 4.2.3 默认值 + 生产实测值）----------
    /** 事件分类，规范默认 1459 */
    private static final String MAT_TYPE = "1459";
    /** 发生地点，规范默认 省公司 */
    private static final String COM_SITE = "省公司";
    /** 事件性质，规范默认 平台告警 */
    private static final String MAT_KIND = "平台告警";
    /** 事件来源，生产实测使用 thdddd（端到端系统接口账号） */
    private static final String MAT_FROM_DEFAULT = "thdddd";
    /** 对象类型，生产实测使用 应用实例 */
    private static final String MO_TYPE_DEFAULT = "应用实例";
    /** 派单意见 */
    private static final String DEALED_IDEA_DEFAULT = "指标体系平台检测到指标异常，请核查处理。";
    /** 操作人姓名（生产实测可用账号） */
    private static final String OPERATOR_PERSON_DEFAULT = "郭兴楠";
    /** 操作人工号 / 告警处理人ID（生产实测可用账号） */
    private static final String OPERATOR_NO_DEFAULT = "blddeveloper";
    /** 操作人部门 */
    private static final String OPERATOR_DEPART_DEFAULT = "某运营商";
    /** 操作人联系方式 */
    private static final String OPERATOR_CONTACT_DEFAULT = "13800000000";
    /** 主要责任人 */
    private static final String RESPONSIBLE_DEFAULT = "郭兴楠";
    /** 次要责任人 */
    private static final String SECOND_RESPONSIBLE_DEFAULT = "赵锦楠";
    /** 所属系统兜底名称 */
    private static final String SYSTEM_TYPE_DEFAULT = "端到端监控系统";

    /** 默认告警级别：2=一般告警 */
    private static final int DEFAULT_ALARM_LEVEL = 2;
    /** 完成期限：问题发生时间 + 7 天（沿用原生产程序逻辑） */
    private static final int COMPLETE_DEADLINE_DAYS = 7;

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 连接超时：内网地址不可达时快速失败，避免拖住告警生成 */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    /** 读取超时：生产实测 createTask 约 0.37s，留足余量 */
    private static final int READ_TIMEOUT_MS = 15000;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private SystemInfoRepository systemInfoRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestTemplate restTemplate = buildRestTemplate();

    /**
     * 构造专用 RestTemplate：设置超时，并强制 UTF-8 解码响应体。
     *
     * <p>原因：BOMC 响应的 Content-Type 不带 charset，Spring 默认按 ISO-8859-1 解码，
     * 会导致返回的中文提示乱码。
     */
    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestTemplate template = new RestTemplate(factory);
        List<HttpMessageConverter<?>> converters = template.getMessageConverters();
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof StringHttpMessageConverter) {
                converters.set(i, new StringHttpMessageConverter(StandardCharsets.UTF_8));
            }
        }
        return template;
    }

    // ==================================================================
    //  对外能力
    // ==================================================================

    /**
     * 创建工单：POST /rest/itsm/v7/wgt/createTask
     *
     * @param gongdan 本地工单（其业务字段会被翻译成 BOMC 的 28 个必填参数）
     * @return 调用结果，成功时携带 BOMC 工单号
     */
    public BomcTaskResult createTask(Gongdan gongdan) {
        if (!enabled) return BomcTaskResult.fail("当前环境未启用 BOMC 对接");
        Map<String, Object> params = buildCreateParams(gongdan);
        Map<String, Object> body = Collections.singletonMap("params", params);
        try {
            String response = post(PATH_CREATE_TASK, body);
            JsonNode root = objectMapper.readTree(response);
            String status = text(root, "status");
            String message = text(root, "message");
            String serial = text(root, "BOMCSerialNo");
            if ("success".equalsIgnoreCase(status) && serial != null && !"-1".equals(serial)) {
                return BomcTaskResult.success(serial, message);
            }
            String failMsg = message != null && !message.isEmpty() ? message : "BOMC 返回失败（status=" + status + "）";
            return BomcTaskResult.fail(failMsg, serial);
        } catch (Exception e) {
            log.error("调用 BOMC 创建工单接口异常: url={}, wgId={}, err={}", getBaseUrl(), params.get("wgId"), e.getMessage());
            return BomcTaskResult.fail("调用 BOMC 创建工单接口异常: " + e.getMessage());
        }
    }

    /**
     * 透明化查询：POST /rest/itsm/v7/wgt/queryTask
     *
     * @param bomcSerialNo BOMC 工单号
     * @return 处理轨迹（当前节点 + 历史节点），失败时 success=false 且 message 为报错信息
     */
    public BomcTaskTrace queryTask(String bomcSerialNo) {
        if (!enabled) {
            var result = new BomcTaskTrace();
            result.setSuccess(false);
            result.setMessage("当前环境未启用 BOMC 对接");
            return result;
        }
        BomcTaskTrace trace = new BomcTaskTrace();
        trace.setBomcSerialNo(bomcSerialNo);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BOMCSerialNo", bomcSerialNo);
        params.put("opPerson", resolveConfig(KEY_OPERATOR_PERSON, OPERATOR_PERSON_DEFAULT));
        params.put("opPersonNo", resolveConfig(KEY_OPERATOR_NO, OPERATOR_NO_DEFAULT));
        params.put("opDepart", resolveConfig(KEY_OPERATOR_DEPART, OPERATOR_DEPART_DEFAULT));
        params.put("opContact", resolveConfig(KEY_OPERATOR_CONTACT, OPERATOR_CONTACT_DEFAULT));

        try {
            String response = post(PATH_QUERY_TASK, Collections.singletonMap("params", params));
            JsonNode root = objectMapper.readTree(response);
            String status = text(root, "status");
            String message = text(root, "message");
            if (!"success".equalsIgnoreCase(status)) {
                trace.setSuccess(false);
                trace.setMessage(message != null && !message.isEmpty() ? message : "BOMC 查询失败（status=" + status + "）");
                return trace;
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                trace.setSuccess(false);
                trace.setMessage("BOMC 未返回处理轨迹数据");
                return trace;
            }
            BomcTaskTrace parsed = objectMapper.treeToValue(data, BomcTaskTrace.class);
            parsed.setSuccess(true);
            parsed.setMessage(message);
            parsed.setBomcSerialNo(bomcSerialNo);
            return parsed;
        } catch (Exception e) {
            log.error("调用 BOMC 透明化查询接口异常: url={}, BOMCSerialNo={}, err={}", getBaseUrl(), bomcSerialNo, e.getMessage());
            trace.setSuccess(false);
            trace.setMessage("调用 BOMC 查询接口异常: " + e.getMessage());
            return trace;
        }
    }

    /**
     * 归档工单：POST /rest/itsm/v7/wgt/archiveTask
     *
     * <p>2026-10-16 生产实测：请求 params 为 BOMCSerialNo + opin(归档原因) + 操作人四要素；
     * 工单未回复完时接口已通但业务拒绝：{@code status=fail}，
     * message=「BOMC工单还未处理完成,请等待回复后再启动归档.」，此处原样透传给调用方。
     *
     * @param bomcSerialNo BOMC 工单号
     * @param opin         归档原因（必填，由界面输入）
     */
    public BomcTaskResult archiveTask(String bomcSerialNo, String opin) {
        return callTaskAction(PATH_ARCHIVE_TASK, "归档", bomcSerialNo, opin);
    }

    /**
     * 撤销工单：POST /rest/itsm/v7/wgt/quashTask
     *
     * <p>2026-10-16 生产实测通过。请求 params 与归档一致，opin 为撤销原因。
     */
    public BomcTaskResult quashTask(String bomcSerialNo, String opin) {
        return callTaskAction(PATH_QUASH_TASK, "撤销", bomcSerialNo, opin);
    }

    /**
     * 归档/撤销通用调用：请求体 { params: { BOMCSerialNo, opin, opPerson, opPersonNo, opDepart, opContact } }。
     * 返回结构与 createTask 一致：status=success 且 BOMCSerialNo 非 -1 才算成功。
     */
    private BomcTaskResult callTaskAction(String path, String actionName, String bomcSerialNo, String opin) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("BOMCSerialNo", bomcSerialNo);
        params.put("opin", opin == null ? "" : opin);
        params.put("opPerson", resolveConfig(KEY_OPERATOR_PERSON, OPERATOR_PERSON_DEFAULT));
        params.put("opPersonNo", resolveConfig(KEY_OPERATOR_NO, OPERATOR_NO_DEFAULT));
        params.put("opDepart", resolveConfig(KEY_OPERATOR_DEPART, OPERATOR_DEPART_DEFAULT));
        params.put("opContact", resolveConfig(KEY_OPERATOR_CONTACT, OPERATOR_CONTACT_DEFAULT));
        try {
            String response = post(path, Collections.singletonMap("params", params));
            JsonNode root = objectMapper.readTree(response);
            String status = text(root, "status");
            String message = text(root, "message");
            String serial = text(root, "BOMCSerialNo");
            if ("success".equalsIgnoreCase(status) && (serial == null || !"-1".equals(serial))) {
                return BomcTaskResult.success(serial != null ? serial : bomcSerialNo, message);
            }
            String failMsg = message != null && !message.isEmpty()
                    ? message : ("BOMC " + actionName + "失败（status=" + status + "）");
            return BomcTaskResult.fail(failMsg, serial);
        } catch (Exception e) {
            log.error("调用 BOMC {}接口异常: url={}, BOMCSerialNo={}, err={}", actionName, getBaseUrl(), bomcSerialNo, e.getMessage());
            return BomcTaskResult.fail("调用 BOMC " + actionName + "接口异常: " + e.getMessage());
        }
    }

    /** 当前生效的 BOMC 服务地址（system_config 优先，其次环境变量 BOMC_BASE_URL，最后生产默认值） */
    public String getBaseUrl() {
        return resolveConfig(KEY_BASE_URL, System.getenv().getOrDefault("BOMC_BASE_URL", DEFAULT_BASE_URL));
    }

    /** 当前生效的操作人 / 处理人信息，供界面展示与排查 */
    public Map<String, String> getOperatorInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("baseUrl", getBaseUrl());
        info.put("opPerson", resolveConfig(KEY_OPERATOR_PERSON, OPERATOR_PERSON_DEFAULT));
        info.put("opPersonNo", resolveConfig(KEY_OPERATOR_NO, OPERATOR_NO_DEFAULT));
        info.put("opDepart", resolveConfig(KEY_OPERATOR_DEPART, OPERATOR_DEPART_DEFAULT));
        info.put("opContact", resolveConfig(KEY_OPERATOR_CONTACT, OPERATOR_CONTACT_DEFAULT));
        info.put("executeUser", resolveConfig(KEY_EXECUTE_USER, OPERATOR_NO_DEFAULT));
        return info;
    }

    /**
     * 保存界面上可直接修改的对接参数到 system_config。
     *
     * <p>规则：
     * <ul>
     *   <li>只接受白名单字段（见 {@link #EDITABLE_CONFIG_KEYS}），未知字段忽略；</li>
     *   <li>值留空 = 删除该覆盖项，恢复代码里的平台默认值；</li>
     *   <li>服务地址必须是 http(s):// 开头，避免填错后所有调用静默失败。</li>
     * </ul>
     *
     * @param changes 界面字段名 → 新值（如 opContact → 13800000000）
     */
    @Transactional
    public void saveConfig(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            String key = EDITABLE_CONFIG_KEYS.get(entry.getKey());
            if (key == null) {
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (KEY_BASE_URL.equals(key) && !value.isEmpty() && !isHttpUrl(value)) {
                throw new IllegalArgumentException("BOMC 服务地址必须以 http:// 或 https:// 开头");
            }
            if (value.isEmpty()) {
                // 留空表示不再覆盖，恢复平台默认值
                systemConfigRepository.findByConfigKey(key).ifPresent(systemConfigRepository::delete);
                log.info("BOMC 配置项 {} 已清空，恢复默认值", key);
                continue;
            }
            SystemConfig config = systemConfigRepository.findByConfigKey(key).orElseGet(() -> {
                SystemConfig created = new SystemConfig();
                created.setConfigKey(key);
                created.setConfigType("BOMC");
                created.setDescription("BOMC 工单接口对接配置（工单配置页可修改）");
                created.setStatus(1);
                return created;
            });
            config.setConfigValue(value);
            systemConfigRepository.save(config);
            log.info("BOMC 配置项 {} 已更新", key);
        }
    }

    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    // ==================================================================
    //  请求报文组装
    // ==================================================================

    /**
     * 按接口规范 4.2.3 组装 createTask 的 28 个必填参数 + 1 个可选参数。
     * 参数顺序与规范一致，便于与规范逐行对照排查。
     */
    private Map<String, Object> buildCreateParams(Gongdan gongdan) {
        LocalDateTime happenTime = firstNonNull(gongdan.getAlarmLastTime(), gongdan.getGongdanTime(), LocalDateTime.now());
        LocalDateTime firstTime = firstNonNull(gongdan.getAlarmFirstTime(), happenTime);
        int level = gongdan.getAlarmLevel() != null ? gongdan.getAlarmLevel() : DEFAULT_ALARM_LEVEL;
        String content = gongdan.getGongdanContent() != null ? gongdan.getGongdanContent() : "";

        Map<String, Object> params = new LinkedHashMap<>();
        // 端到端工单唯一标识：用本地工单号，保证 BOMC 工单可反查回平台
        params.put("wgId", wgId(gongdan));
        // 工单标题
        params.put("task_title", truncate(gongdan.getGongdanTitle(), 500));
        // 派单意见
        params.put("task_dealed_idea", resolveConfig(KEY_DEALED_IDEA, DEALED_IDEA_DEFAULT));
        // 工单描述
        params.put("com_desc", truncate(content, 500));
        // 问题发生时间
        params.put("mat_happen_date", DATETIME.format(happenTime));
        // 事件分类 / 发生地点 / 事件性质 / 事件来源（固定值）
        params.put("mat_type", MAT_TYPE);
        params.put("com_site", COM_SITE);
        params.put("mat_kind", MAT_KIND);
        params.put("mat_from", resolveConfig(KEY_EVENT_FROM, MAT_FROM_DEFAULT));
        // 是否重复
        params.put("mat_repeat", "false");
        // 优先级 / 影响度：工单未指定时按告警级别推导
        params.put("mat_pri", notBlankOr(gongdan.getAlarmPriority(), priorityOf(level)));
        params.put("mat_incid", notBlankOr(gongdan.getAlarmImpact(), impactOf(level)));
        // 告警对象名称
        params.put("mo_name", truncate(notBlankOr(gongdan.getAlarmObject(), gongdan.getGongdanTitle()), 200));
        // 告警内容
        params.put("mo_alarm_content", truncate(content, 1500));
        // 对象类型
        params.put("mo_type", resolveConfig(KEY_MO_TYPE, MO_TYPE_DEFAULT));
        // 完成期限
        params.put("mat_compl", DATETIME.format(happenTime.plusDays(COMPLETE_DEADLINE_DAYS)));
        // 告警级别
        params.put("mo_alarm_currentgrade", String.valueOf(level));
        // 第一次 / 最后一次发生时间
        params.put("mo_alarm_firstoccurtime", DATETIME.format(firstTime));
        params.put("mo_alarm_lastoccurtime", DATETIME.format(happenTime));
        // 告警次数
        params.put("mo_alarm_count", String.valueOf(
                gongdan.getAlarmCount() != null && gongdan.getAlarmCount() > 0 ? gongdan.getAlarmCount() : 1));
        // 所属主机IP（非必填，留空）
        params.put("mo_belongtohostip", "");
        // 主要责任人 / 次要责任人
        params.put("mo_resoonsible", notBlankOr(gongdan.getResponsiblePerson(),
                resolveConfig(KEY_RESPONSIBLE, RESPONSIBLE_DEFAULT)));
        params.put("mo_secondresp", notBlankOr(gongdan.getSecondResponsible(),
                resolveConfig(KEY_SECOND_RESPONSIBLE, SECOND_RESPONSIBLE_DEFAULT)));
        // 所属业务系统名称
        params.put("com_system_type", resolveSystemType(gongdan));
        // 告警处理人（BOMC 系统人员ID）
        params.put("task_execute_user", notBlankOr(gongdan.getExecuteUser(),
                resolveConfig(KEY_EXECUTE_USER, OPERATOR_NO_DEFAULT)));
        // 操作人四要素（BOMC 日志表记录，不显示在界面）
        params.put("opPerson", resolveConfig(KEY_OPERATOR_PERSON, OPERATOR_PERSON_DEFAULT));
        params.put("opPersonNo", resolveConfig(KEY_OPERATOR_NO, OPERATOR_NO_DEFAULT));
        params.put("opDepart", resolveConfig(KEY_OPERATOR_DEPART, OPERATOR_DEPART_DEFAULT));
        params.put("opContact", resolveConfig(KEY_OPERATOR_CONTACT, OPERATOR_CONTACT_DEFAULT));
        return params;
    }

    /**
     * 端到端工单唯一标识：优先用本地工单号（便于回查），没有则按生产实测格式随机生成。
     */
    private String wgId(Gongdan gongdan) {
        String gongdanId = gongdan.getGongdanId();
        if (gongdanId != null && !gongdanId.trim().isEmpty()) {
            return gongdanId.trim();
        }
        return "wg" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    /** 所属业务系统名称：优先取本地系统名，取不到再用配置/默认值 */
    private String resolveSystemType(Gongdan gongdan) {
        if (gongdan.getSystemId() != null) {
            SystemInfo system = systemInfoRepository.findById(gongdan.getSystemId()).orElse(null);
            if (system != null && system.getSystemName() != null && !system.getSystemName().trim().isEmpty()) {
                return truncate(system.getSystemName(), 20);
            }
        }
        return resolveConfig(KEY_SYSTEM_TYPE, SYSTEM_TYPE_DEFAULT);
    }

    /** 告警级别 → 优先级：1-警告→低，2-一般→中，3/4→高 */
    private static String priorityOf(int level) {
        if (level <= 1) {
            return "低";
        }
        if (level == 2) {
            return "中";
        }
        return "高";
    }

    /** 告警级别 → 影响度：1/2→一般，3→重大，4→严重 */
    private static String impactOf(int level) {
        if (level >= 4) {
            return "严重";
        }
        if (level == 3) {
            return "重大";
        }
        return "一般";
    }

    // ==================================================================
    //  通用工具
    // ==================================================================

    /** 发送 POST JSON 请求，返回响应体字符串；非 2xx 直接抛异常 */
    private String post(String path, Map<String, Object> body) throws Exception {
        String url = getBaseUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        headers.set(HttpHeaders.ACCEPT_CHARSET, "utf-8");
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return response.getBody();
    }

    /** 读取 system_config 中的配置值，未配置或为空时返回默认值 */
    private String resolveConfig(String key, String defaultValue) {
        try {
            return systemConfigRepository.findByConfigKey(key)
                    .map(SystemConfig::getConfigValue)
                    .filter(v -> v != null && !v.trim().isEmpty())
                    .map(String::trim)
                    .orElse(defaultValue);
        } catch (Exception e) {
            log.warn("读取 BOMC 配置项 {} 失败，使用默认值: {}", key, e.getMessage());
            return defaultValue;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static String notBlankOr(String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value.trim() : fallback;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
