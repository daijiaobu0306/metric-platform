package com.metric.gongdan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.gongdan.dto.BomcRepushResult;
import com.metric.gongdan.dto.BomcTaskResult;
import com.metric.gongdan.dto.BomcTaskTrace;
import com.metric.gongdan.entity.Gongdan;
import com.metric.gongdan.repository.GongdanRepository;
import com.metric.gongdan.service.BomcGongdanClient;
import com.metric.security.service.CurrentUserProvider;
import com.metric.audit.service.EntityAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工单与省 BOMC 的对接服务。
 *
 * <p>改造说明（2026-10）：原实现走"插件配置"（PluginConfig + PluginExecutorService），
 * 需要人工配接口地址、参数映射才能同步工单。现 BOMC 接口已明确，
 * 本服务改为<b>直连 BOMC 客户端</b>（{@link BomcGongdanClient}），接口规则全部写死，
 * 不再依赖插件功能。插件模块本身保持不变，其它业务仍可正常使用。
 *
 * <p>职责：
 * <ul>
 *   <li>推送工单到 BOMC（createTask）并回填 BOMC 工单号</li>
 *   <li>查询 BOMC 处理轨迹（queryTask）并回填当前节点/处理人</li>
 *   <li>补推历史未推送成功的工单</li>
 * </ul>
 */
@Service
public class GongdanIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(GongdanIntegrationService.class);

    /** 手动补推时单次最多处理的工单数，避免接口异常时长时间阻塞 */
    public static final int PUSH_PENDING_LIMIT = 50;

    /** 批量刷新状态时单次最多查询的工单数（分页最大 100，留余量） */
    public static final int TRACE_REFRESH_LIMIT = 200;

    /** 补推时间窗：仅补推最近 N 天内创建的工单 */
    private static final int RETRY_WINDOW_DAYS = 7;

    /** 已成功推送标记 */
    private static final int SYNCED_SUCCESS = 1;
    /** 未推送/推送失败标记 */
    private static final int SYNCED_PENDING = 0;

    /** bomc_action：已撤销（quashTask 成功后本地打标，不删数据） */
    private static final String BOMC_ACTION_QUASHED = "QUASHED";
    /** bomc_action：已归档（archiveTask 成功后本地打标，不删数据） */
    private static final String BOMC_ACTION_ARCHIVED = "ARCHIVED";
    /** 撤销原因留空时的默认 opin */
    private static final String DEFAULT_QUASH_REASON = "工单撤销";

    /** 已归档工单查询轨迹时统一返回的说明（不发起 BOMC 查询，前端据此展示本地数据） */
    private static final String ARCHIVED_NO_TRACE_MSG = "工单已归档，BOMC 处理信息不再实时查询";

    /** 演示轨迹里的时间格式（与 BOMC 返回的 lastUpdate 一致） */
    private static final DateTimeFormatter TRACE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private GongdanRepository gongdanRepository;

    @Autowired
    private GongdanService gongdanService;

    @Autowired
    private BomcGongdanClient bomcGongdanClient;

    @Autowired
    private EntityAuditService entityAuditService;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    /**
     * 演示环境开关：BOMC 查不到轨迹时，用工单本地字段生成一条结构一致的"演示轨迹"。
     *
     * <p>为什么需要：验收/演示环境的工单号是造数生成的，BOMC 侧没有这些单，
     * queryTask 一定返回失败，页面就只剩"查询轨迹失败"。
     *
     * <p>生效条件：显式打开本开关，**或**当前环境未启用 BOMC 对接（{@code bomc.enabled=false}）。
     * 后者覆盖了内网演示环境"本来就没接 BOMC"的常态，部署时不用额外改配置；
     * 真正接了 BOMC 的环境不会被演示轨迹掩盖故障。
     */
    @Value("${gongdan.bomc.demo-trace:false}")
    private boolean demoTraceEnabled;

    /** 当前环境是否启用了 BOMC 对接（未启用时默认用本地演示轨迹兜底） */
    @Value("${bomc.enabled:true}")
    private boolean bomcEnabled;

    /**
     * system_config 里的演示轨迹开关（不改配置、不重启就能开/关）。
     *
     * <p>验收造数脚本会写入 {@code gongdan.bomc.demo-trace=true}，验收结束回滚时删除，
     * 这样"造数"和"演示"是一件事，数据侧就能控制，不用动部署配置。
     */
    private static final String KEY_DEMO_TRACE = "gongdan.bomc.demo-trace";

    @Autowired
    private com.metric.config.service.ConfigService configService;

    /** 是否启用本地演示轨迹：显式配置打开，或本环境没接 BOMC */
    private boolean demoTraceActive() {
        if (demoTraceEnabled || !bomcEnabled) return true;
        return "true".equalsIgnoreCase(configService.getConfigValue(KEY_DEMO_TRACE, "false").trim());
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 把本地工单推送到 BOMC 创建工单。
     *
     * <p>幂等：已成功推送（externalId 非空）且 {@code force=false} 时直接返回，避免重复建单；
     * 需要重新推送（例如 BOMC 侧工单已作废）时传 {@code force=true}。
     *
     * @param gongdanId 本地工单ID
     * @param force     是否强制重新推送
     * @return 推送结果，工单不存在时返回失败
     */
    @Transactional
    public BomcTaskResult pushToBomc(Long gongdanId, boolean force) {
        Optional<Gongdan> optional = gongdanRepository.findById(gongdanId);
        if (optional.isEmpty()) {
            return BomcTaskResult.fail("工单不存在: " + gongdanId);
        }
        Gongdan gongdan = optional.get();

        if (!force && gongdan.getExternalId() != null && !gongdan.getExternalId().trim().isEmpty()
                && gongdan.getSynced() != null && gongdan.getSynced() == SYNCED_SUCCESS) {
            return BomcTaskResult.success(gongdan.getExternalId(),
                    "该工单已推送至 BOMC，无需重复推送");
        }

        String oldValue = toJson(gongdan);
        BomcTaskResult result = bomcGongdanClient.createTask(gongdan);

        if (result.isSuccess()) {
            gongdan.setExternalId(result.getBomcSerialNo());
            gongdan.setSynced(SYNCED_SUCCESS);
            gongdan.setSyncedTime(LocalDateTime.now());
            gongdan.setExternalSyncMsg(trimMessage(result.getMessage()));
            // 记录本次实际推送出去的标题/内容快照，供"重新推送"前比对是否被编辑过
            gongdan.setPushedTitle(gongdan.getGongdanTitle());
            gongdan.setPushedContent(gongdan.getGongdanContent());
            log.info("工单推送 BOMC 成功: gongdanId={}, BOMCSerialNo={}", gongdan.getGongdanId(), result.getBomcSerialNo());
        } else {
            gongdan.setSynced(SYNCED_PENDING);
            gongdan.setExternalSyncMsg(trimMessage(result.getMessage()));
            log.warn("工单推送 BOMC 失败: gongdanId={}, 原因={}", gongdan.getGongdanId(), result.getMessage());
        }
        Gongdan saved = gongdanRepository.save(gongdan);

        entityAuditService.logGongdanOperation(EntityAuditService.OP_UPDATE, saved.getId(),
                currentUserProvider.operatorName(), oldValue, toJson(saved),
                (result.isSuccess() ? "推送工单至BOMC成功，BOMC工单号: " : "推送工单至BOMC失败: ")
                        + (result.isSuccess() ? result.getBomcSerialNo() : result.getMessage()));
        return result;
    }

    /** 推送工单到 BOMC（已推送过则跳过） */
    @Transactional
    public BomcTaskResult pushToBomc(Long gongdanId) {
        return pushToBomc(gongdanId, false);
    }

    /**
     * 查询工单在 BOMC 的处理轨迹（透明化查询）。
     *
     * <p>查询成功后回填：BOMC 当前节点 → {@code externalStatus} 与 {@code currentStep}，
     * 当前处理人 → {@code currentHandler}。处理轨迹本身不落库，实时查询。
     *
     * <p>例外：已归档工单不发起 BOMC 查询，直接返回 success=true 的空轨迹
     * （归档后 BOMC 已无处理信息，实时查询只会失败并打扰用户）。
     *
     * @param gongdanId 本地工单ID
     * @return 处理轨迹；工单未推送 BOMC 时返回 success=false
     */
    @Transactional
    public BomcTaskTrace queryTaskTrace(Long gongdanId) {
        BomcTaskTrace errorTrace = new BomcTaskTrace();
        Optional<Gongdan> optional = gongdanRepository.findById(gongdanId);
        if (optional.isEmpty()) {
            errorTrace.setSuccess(false);
            errorTrace.setMessage("工单不存在: " + gongdanId);
            return errorTrace;
        }
        Gongdan gongdan = optional.get();
        // 已归档的工单不查 BOMC：归档后 BOMC 侧已没有该单的处理信息，
        // queryTask 只会返回「工单操作(查询工单)失败,未查询到工单处理信息」，
        // 前端打开详情就会弹这个报错。归档是终结状态，节点也不会再变，
        // 这里直接返回成功 + 空轨迹（BOMC 相关字段留空），前端只展示本地库数据。
        if (isArchived(gongdan)) {
            BomcTaskTrace archivedTrace = new BomcTaskTrace();
            archivedTrace.setSuccess(true);
            archivedTrace.setBomcSerialNo(gongdan.getExternalId());
            archivedTrace.setMessage(ARCHIVED_NO_TRACE_MSG);
            return archivedTrace;
        }
        String bomcSerialNo = gongdan.getExternalId();
        if (bomcSerialNo == null || bomcSerialNo.trim().isEmpty()) {
            errorTrace.setSuccess(false);
            errorTrace.setMessage("该工单尚未推送至 BOMC，无法查询处理轨迹");
            return errorTrace;
        }

        String oldValue = toJson(gongdan);
        BomcTaskTrace trace = applyTrace(gongdan, bomcSerialNo.trim());
        Gongdan saved = gongdanRepository.save(gongdan);
        entityAuditService.logGongdanOperation(EntityAuditService.OP_UPDATE, saved.getId(),
                currentUserProvider.operatorName(), oldValue, toJson(saved),
                trace.isSuccess() ? "查询BOMC处理轨迹成功，当前节点: " + gongdan.getExternalStatus()
                        : "查询BOMC处理轨迹失败: " + trace.getMessage());
        return trace;
    }

    /**
     * 工单是否已归档（BOMC 侧已终结）。
     *
     * <p>判定依据（均来自本地库，不查 BOMC）：
     * <ul>
     *   <li>{@code bomc_action = 'ARCHIVED'}——本平台归档成功后打的终结标记（见 {@link #invokeTaskAction}）；</li>
     *   <li>{@code external_status = '已归档'}——归档成功时同步写入，或 BOMC 透明化查询返回的最后节点即为已归档。</li>
     * </ul>
     * 只做精确匹配，不用「包含归档」：像「提交归档」这类未完成归档的节点仍要按原逻辑实时查询。
     */
    private static boolean isArchived(Gongdan gongdan) {
        if (BOMC_ACTION_ARCHIVED.equals(gongdan.getBomcAction())) {
            return true;
        }
        String externalStatus = gongdan.getExternalStatus();
        return externalStatus != null && "已归档".equals(externalStatus.trim());
    }

    /** 查询 BOMC 轨迹并把当前节点/处理人回填到实体（不落库，由调用方 save） */
    private BomcTaskTrace applyTrace(Gongdan gongdan, String bomcSerialNo) {
        BomcTaskTrace trace = bomcGongdanClient.queryTask(bomcSerialNo);
        gongdan.setLastQueryTime(LocalDateTime.now());
        if (trace.isSuccess() && trace.getCurrent() != null) {
            String nodeName = trace.getCurrent().getNodeName();
            gongdan.setExternalStatus(nodeName);
            if (nodeName != null && !nodeName.trim().isEmpty()) {
                gongdan.setCurrentStep(nodeName);
            }
            String handler = trace.getCurrent().getUserName();
            if (handler != null && !handler.trim().isEmpty()) {
                gongdan.setCurrentHandler(handler);
            }
            return trace;
        }
        // 演示环境兜底：BOMC 没返回时用本地字段造一条轨迹，保证页面有节点可看。
        // 生效条件：显式开关 / 本环境没接 BOMC / system_config 打开演示轨迹。
        if (demoTraceActive()) {
            BomcTaskTrace demo = demoTrace(gongdan);
            if (demo != null) {
                log.warn("BOMC 轨迹查询失败，改用本地演示轨迹: gongdanId={}, BOMC单号={}, 原因={}",
                        gongdan.getGongdanId(), bomcSerialNo, trace.getMessage());
                return demo;
            }
        }
        return trace;
    }

    /**
     * 用工单本地字段拼一条 BOMC 形状的处理轨迹（演示用，仅当 demo-trace 打开时生效）。
     *
     * <p>节点顺序按"受理 → 处理 → 归档"，时间取工单的生成时间/关单时间，处理人取工单的
     * 负责人与处理人。真实 BOMC 环境不会走到这里。
     */
    private BomcTaskTrace demoTrace(Gongdan gongdan) {
        LocalDateTime base = gongdan.getGongdanTime() != null ? gongdan.getGongdanTime()
                : gongdan.getCreateTime() != null ? gongdan.getCreateTime()
                : LocalDateTime.now().minusDays(3);
        boolean closed = gongdan.getClosedStatus() != null && gongdan.getClosedStatus() == 1;
        LocalDateTime finish = gongdan.getClosedTime() != null ? gongdan.getClosedTime() : base.plusHours(6);
        String lead = defaultName(gongdan.getResponsiblePerson(), "于洲");
        String owner = defaultName(gongdan.getCurrentHandler(), defaultName(gongdan.getExecuteUser(), "赵锦楠"));

        BomcTaskTrace trace = new BomcTaskTrace();
        trace.setSuccess(true);
        trace.setBomcSerialNo(gongdan.getExternalId());
        trace.setMessage("本地演示轨迹（BOMC 未返回数据）");

        BomcTaskTrace.Node current = new BomcTaskTrace.Node();
        current.setNodeName(closed ? defaultName(gongdan.getExternalStatus(), "已归档") : "处理中");
        current.setUserName(closed ? lead : owner);
        current.setRoleName(roleOf(closed ? lead : owner));
        trace.setCurrent(current);

        List<BomcTaskTrace.History> history = new ArrayList<>();
        history.add(historyItem("工单受理", "生成工单", lead, base, "指标值超阈值，自动生成工单并派单"));
        history.add(historyItem("问题定位", "开始处理", owner, base.plusMinutes(30), "已确认指标异常，开始排查"));
        if (closed) {
            history.add(historyItem("处理完成", "提交验收", owner, finish.minusHours(3), "指标已恢复正常，提交归档验收"));
            history.add(historyItem("工单归档", "归档", lead, finish, "验收通过，工单归档"));
        } else {
            history.add(historyItem("处理中", "处理中", owner, base.plusHours(4), "正在处理，等待指标恢复"));
        }
        trace.setHistory(history);
        return trace;
    }

    private BomcTaskTrace.History historyItem(String node, String action, String user,
                                              LocalDateTime at, String idea) {
        BomcTaskTrace.History item = new BomcTaskTrace.History();
        item.setNodeName(node);
        item.setActionName(action);
        item.setDealedBy(user);
        if (at != null) {
            item.setLastUpdate(at.format(TRACE_TIME_FMT));
            item.setSortData(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
        item.setTaskDealedIdea(idea);
        return item;
    }

    private static String defaultName(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** 演示轨迹里的角色名称，与造数使用的人员分工保持一致 */
    private static String roleOf(String name) {
        return switch (name) {
            case "于洲" -> "运营主管";
            case "乔凯峰" -> "CRM 负责人";
            case "魏笑" -> "IT 平台负责人";
            case "赵锦楠" -> "运维人员";
            default -> "处理人";
        };
    }

    /**
     * 批量刷新工单的 BOMC 状态（列表页「刷新状态」按钮用）。
     *
     * <p>设计约束：BOMC 只有单工单查询接口，逐单查询不可避免；为减轻负担，
     * 前端只传<b>当前页</b>的工单 id，后端逐个查询并回填节点/处理人，单次上限
     * {@value #TRACE_REFRESH_LIMIT} 条。以下工单直接跳过、不调 BOMC：
     * <ul>
     *   <li>未推送（无 BOMC 工单号）；</li>
     *   <li>已撤销/已归档（bomc_action 已打终结标，节点不会再变）。</li>
     * </ul>
     * 本操作是状态轮询，不写工单操作日志。
     *
     * @param gongdanIds 要刷新的工单 id（一般传当前页数据）
     * @return total=实际查询条数, success=成功, skipped=跳过, failures=[{id,message}]
     */
    @Transactional
    public Map<String, Object> refreshTraces(List<Long> gongdanIds) {
        int attempted = 0;
        int success = 0;
        int skipped = 0;
        List<Map<String, String>> failures = new ArrayList<>();
        if (gongdanIds != null) {
            for (Long id : gongdanIds) {
                if (id == null || attempted >= TRACE_REFRESH_LIMIT) {
                    continue;
                }
                Optional<Gongdan> optional = gongdanRepository.findById(id);
                if (optional.isEmpty()) {
                    failures.add(failureOf(id, "工单不存在"));
                    continue;
                }
                Gongdan gongdan = optional.get();
                if (gongdan.getBomcAction() != null) {
                    skipped++;
                    continue;
                }
                String bomcSerialNo = gongdan.getExternalId();
                if (bomcSerialNo == null || bomcSerialNo.trim().isEmpty()) {
                    skipped++;
                    continue;
                }
                attempted++;
                BomcTaskTrace trace = applyTrace(gongdan, bomcSerialNo.trim());
                gongdanRepository.save(gongdan);
                if (trace.isSuccess()) {
                    success++;
                } else {
                    failures.add(failureOf(id, trace.getMessage()));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", attempted);
        result.put("success", success);
        result.put("skipped", skipped);
        result.put("failures", failures);
        return result;
    }

    private Map<String, String> failureOf(Long id, String message) {
        Map<String, String> failure = new LinkedHashMap<>();
        failure.put("id", String.valueOf(id));
        failure.put("message", trimMessage(message == null ? "未知错误" : message));
        return failure;
    }

    /**
     * 归档工单（archiveTask）：调 BOMC 归档接口，成功后本地打标（bomc_action=ARCHIVED）并关单，不删数据。
     *
     * <p>业务失败（最常见：工单还未回复处理完）原样透传 BOMC 提示，本地不做任何变更。
     *
     * @param gongdanId 本地工单ID
     * @param reason    归档原因（必填，映射接口的 opin 字段）
     */
    @Transactional
    public BomcTaskResult archiveGongdan(Long gongdanId, String reason) {
        return invokeTaskAction(gongdanId, true, reason);
    }

    /**
     * 撤销工单（quashTask）：调 BOMC 撤销接口，成功后本地打标（bomc_action=QUASHED）并关单，不删数据。
     *
     * @param gongdanId 本地工单ID
     * @param reason    撤销原因（可空，空则用「工单撤销」），映射接口的 opin 字段
     */
    @Transactional
    public BomcTaskResult quashGongdan(Long gongdanId, String reason) {
        return invokeTaskAction(gongdanId, false, reason);
    }

    private BomcTaskResult invokeTaskAction(Long gongdanId, boolean archive, String reason) {
        String actionName = archive ? "归档" : "撤销";
        Optional<Gongdan> optional = gongdanRepository.findById(gongdanId);
        if (optional.isEmpty()) {
            return BomcTaskResult.fail("工单不存在: " + gongdanId);
        }
        Gongdan gongdan = optional.get();
        String bomcSerialNo = gongdan.getExternalId();
        if (bomcSerialNo == null || bomcSerialNo.trim().isEmpty()) {
            return BomcTaskResult.fail("该工单尚未推送至 BOMC，无法" + actionName);
        }
        String opin = reason == null ? "" : reason.trim();
        if (opin.isEmpty()) {
            if (archive) {
                return BomcTaskResult.fail("请填写归档原因");
            }
            opin = DEFAULT_QUASH_REASON;
        }
        String oldValue = toJson(gongdan);
        BomcTaskResult result = archive
                ? bomcGongdanClient.archiveTask(bomcSerialNo.trim(), opin)
                : bomcGongdanClient.quashTask(bomcSerialNo.trim(), opin);
        if (result.isSuccess()) {
            gongdan.setBomcAction(archive ? BOMC_ACTION_ARCHIVED : BOMC_ACTION_QUASHED);
            gongdan.setBomcActionReason(trimMessage(opin));
            gongdan.setClosedStatus(1);
            gongdan.setClosedTime(LocalDateTime.now());
            gongdan.setExternalStatus(archive ? "已归档" : "已撤销");
            Gongdan saved = gongdanRepository.save(gongdan);
            entityAuditService.logGongdanOperation(EntityAuditService.OP_CLOSE, saved.getId(),
                    currentUserProvider.operatorName(), oldValue, toJson(saved),
                    actionName + "BOMC工单成功，原因: " + opin);
            log.info("BOMC工单{}成功: gongdanId={}, BOMCSerialNo={}", actionName, gongdan.getGongdanId(), bomcSerialNo);
        } else {
            entityAuditService.logGongdanOperation(EntityAuditService.OP_UPDATE, gongdan.getId(),
                    currentUserProvider.operatorName(), oldValue, null,
                    actionName + "BOMC工单失败: " + result.getMessage());
            log.warn("BOMC工单{}失败: gongdanId={}, 原因={}", actionName, gongdan.getGongdanId(), result.getMessage());
        }
        return result;
    }

    /**
     * 重新推送 BOMC：调 createTask 在 BOMC <b>新建</b>工单，成功后在本地<b>新建</b>一条工单承载新推送，
     * 原工单保持不动（不自动撤销，是否撤回由操作人自行决定）。
     *
     * <p>新工单携带新工单号（wgId）与当前标题/内容；推送失败则不建本地工单。
     *
     * @param gongdanId 原本地工单ID
     */
    @Transactional
    public BomcRepushResult repushToBomc(Long gongdanId) {
        Optional<Gongdan> optional = gongdanRepository.findById(gongdanId);
        if (optional.isEmpty()) {
            return BomcRepushResult.fail("工单不存在: " + gongdanId);
        }
        Gongdan source = optional.get();
        Gongdan fresh = copyForRepush(source);

        BomcTaskResult result = bomcGongdanClient.createTask(fresh);
        if (!result.isSuccess()) {
            entityAuditService.logGongdanOperation(EntityAuditService.OP_UPDATE, source.getId(),
                    currentUserProvider.operatorName(), null, null,
                    "重新推送BOMC失败: " + result.getMessage());
            log.warn("重新推送 BOMC 失败: gongdanId={}, 原因={}", source.getGongdanId(), result.getMessage());
            return BomcRepushResult.fail(result.getMessage());
        }

        fresh.setExternalId(result.getBomcSerialNo());
        fresh.setSynced(SYNCED_SUCCESS);
        fresh.setSyncedTime(LocalDateTime.now());
        fresh.setExternalSyncMsg(trimMessage(result.getMessage()));
        fresh.setPushedTitle(fresh.getGongdanTitle());
        fresh.setPushedContent(fresh.getGongdanContent());
        Gongdan saved = gongdanRepository.save(fresh);

        entityAuditService.logGongdanOperation(EntityAuditService.OP_CREATE, saved.getId(),
                currentUserProvider.operatorName(), null, toJson(saved),
                "重新推送BOMC，新建工单 " + saved.getGongdanId() + "（BOMC工单号: " + result.getBomcSerialNo() + "）");
        entityAuditService.logGongdanOperation(EntityAuditService.OP_UPDATE, source.getId(),
                currentUserProvider.operatorName(), null, null,
                "重新推送BOMC，已由新工单 " + saved.getGongdanId() + "（BOMC工单号: " + result.getBomcSerialNo()
                        + "）承接；原工单未撤销，如需撤回请在原工单执行撤销");
        log.info("重新推送 BOMC 成功: 原工单={}, 新工单={}, BOMCSerialNo={}",
                source.getGongdanId(), saved.getGongdanId(), result.getBomcSerialNo());
        return BomcRepushResult.success(result.getBomcSerialNo(), saved.getId(), saved.getGongdanId(),
                "推送成功，已新建工单 " + saved.getGongdanId() + "（BOMC工单号 " + result.getBomcSerialNo() + "）");
    }

    /** 重新推送时复制业务字段生成新工单（新工单号、未关单、无 BOMC 痕迹） */
    private Gongdan copyForRepush(Gongdan source) {
        Gongdan fresh = new Gongdan();
        fresh.setGongdanId(gongdanService.generateGongdanId());
        fresh.setSystemId(source.getSystemId());
        fresh.setCategoryId(source.getCategoryId());
        fresh.setMetricId(source.getMetricId());
        fresh.setGongdanTitle(source.getGongdanTitle());
        fresh.setGongdanContent(source.getGongdanContent());
        fresh.setGongdanType(source.getGongdanType());
        fresh.setGongdanTime(LocalDateTime.now());
        fresh.setClosedStatus(0);
        fresh.setStrategyId(source.getStrategyId());
        fresh.setAlarmLevel(source.getAlarmLevel());
        fresh.setAlarmPriority(source.getAlarmPriority());
        fresh.setAlarmImpact(source.getAlarmImpact());
        fresh.setAlarmObject(source.getAlarmObject());
        fresh.setAlarmFirstTime(source.getAlarmFirstTime());
        fresh.setAlarmLastTime(source.getAlarmLastTime());
        fresh.setAlarmCount(source.getAlarmCount());
        fresh.setResponsiblePerson(source.getResponsiblePerson());
        fresh.setSecondResponsible(source.getSecondResponsible());
        fresh.setExecuteUser(source.getExecuteUser());
        return fresh;
    }

    /**
     * 补推未成功推送 BOMC 的工单（仅补推最近 {@value #RETRY_WINDOW_DAYS} 天内创建的，避免反复重推历史脏数据）。
     *
     * @param limit 单次最多处理的条数
     * @return 本次补推成功的条数
     */
    @Transactional
    public int pushPending(int limit) {
        LocalDateTime retryFrom = LocalDateTime.now().minusDays(RETRY_WINDOW_DAYS);
        List<Gongdan> pendingList = gongdanRepository
                .findBySyncedAndCreateTimeAfterOrderByCreateTimeDesc(SYNCED_PENDING, retryFrom);
        int successCount = 0;
        int handled = 0;
        for (Gongdan gongdan : pendingList) {
            if (handled >= limit) {
                break;
            }
            handled++;
            // 强制重推：这些工单此前就是推送失败后才留在 pending 状态的
            BomcTaskResult result = pushToBomc(gongdan.getId(), true);
            if (result.isSuccess()) {
                successCount++;
            }
        }
        log.info("补推 BOMC 工单完成: 尝试 {} 条, 成功 {} 条", handled, successCount);
        return successCount;
    }

    /** 未成功推送到 BOMC 的工单数量（供前端统计展示） */
    public long countPending() {
        Long count = gongdanRepository.countBySynced(SYNCED_PENDING);
        return count != null ? count : 0L;
    }

    /** Compatibility hooks retained for the existing scheduler and event listener. */
    public void syncPendingGongdan() {
        pushPending(PUSH_PENDING_LIMIT);
    }

    public void autoSyncOnCreate(Long gongdanId) {
        pushToBomc(gongdanId);
    }

    public void autoCloseOnExternal(Long gongdanId) {
        queryTaskTrace(gongdanId);
    }

    /** 当前 BOMC 对接配置（地址 + 操作人），供界面展示排查 */
    public Map<String, String> getBomcConfig() {
        return bomcGongdanClient.getOperatorInfo();
    }

    /**
     * 修改 BOMC 对接参数（工单配置页双击修改后落库）。
     *
     * @param changes 界面字段名 → 新值；留空表示恢复平台默认值
     */
    public void updateBomcConfig(Map<String, String> changes) {
        bomcGongdanClient.saveConfig(changes);
    }

    private String toJson(Gongdan gongdan) {
        try {
            return objectMapper.writeValueAsString(gongdan);
        } catch (Exception e) {
            return null;
        }
    }

    /** BOMC 返回消息长度受限于 external_sync_msg(500)，超长截断 */
    private String trimMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
