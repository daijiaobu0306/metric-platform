package com.metric.observe.service;

import com.metric.observe.dto.DashboardPolicy;
import com.metric.observe.entity.DashboardViewConfig;
import com.metric.observe.repository.DashboardViewConfigRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import static com.metric.observe.service.DashboardAccessService.id;

/** Read-only dedicated-screen queries. No writes to other business modules. */
@Service
public class DashboardWorkspaceService {
    private final DashboardAccessService access;
    private final DashboardViewConfigRepository repository;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate sql;
    private final HealthSnapshotQueryService snapshots;
    private final com.metric.metric.service.MetricObservationReadService observationReads;
    public DashboardWorkspaceService(DashboardAccessService access,DashboardViewConfigRepository repository,JdbcTemplate jdbc,HealthSnapshotQueryService snapshots,com.metric.metric.service.MetricObservationReadService observationReads){
        this.access=access;this.repository=repository;this.jdbc=jdbc;this.sql=new NamedParameterJdbcTemplate(jdbc);this.snapshots=snapshots;this.observationReads=observationReads;
    }

    /** 自定义时间区间：start/end 来自 startTime/endTime 参数，days 为按窗口长度换算后的等效粒度。 */
    public record TimeRange(LocalDateTime start,LocalDateTime end,int days){}
    /** 自定义区间上限：90 天。历史指标/明细表按天增长，超过上限单次请求会长时间扫表。 */
    private static final int MAX_CUSTOM_RANGE_DAYS=90;
    private static final DateTimeFormatter RANGE_TIME_FORMAT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RANGE_MINUTE_FORMAT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 统一解析工作台/分享接口的可选自定义时间区间。
     *
     * <p>两个参数都不传时返回 {@code null}，调用方继续按 days（1/7/30）走原有逻辑，
     * 老链接不受影响；只传一个、格式错误、start 不早于 end 或超过 90 天都返回 400 中文提示。
     * days 按窗口长度换算成等价的 1/7/30，保证健康度趋势 range（1d/7d/30d）
     * 以及依赖 days 的分支在自定义区间下仍然可用。
     */
    public static TimeRange resolveRange(String startTime,String endTime){
        boolean hasStart=startTime!=null&&!startTime.isBlank();
        boolean hasEnd=endTime!=null&&!endTime.isBlank();
        if(!hasStart&&!hasEnd) return null;
        if(hasStart!=hasEnd) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"自定义时间区间需同时提供开始时间和结束时间");
        LocalDateTime start=parseRangeTime(startTime,"开始时间");
        LocalDateTime end=parseRangeTime(endTime,"结束时间");
        if(!start.isBefore(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"自定义时间区间的开始时间必须早于结束时间");
        long minutes=Duration.between(start,end).toMinutes();
        if(minutes>MAX_CUSTOM_RANGE_DAYS*24L*60L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"自定义时间区间不能超过90天");
        // 趋势/健康度快照只有 1d/7d/30d 三档，按窗口长度向上取最接近的一档。
        int days=minutes<=24L*60L?1:minutes<=7L*24L*60L?7:30;
        return new TimeRange(start,end,days);
    }

    /** 允许前端传 ISO 的 T 分隔写法；秒可省略。 */
    private static LocalDateTime parseRangeTime(String value,String label){
        String text=value.trim().replace('T',' ');
        DateTimeFormatter formatter=text.length()>16?RANGE_TIME_FORMAT:RANGE_MINUTE_FORMAT;
        try{return LocalDateTime.parse(text,formatter);}
        catch(DateTimeParseException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,label+"格式错误，应为 yyyy-MM-dd HH:mm:ss");}
    }

    /**
     * 告警阈值的取值表达式。
     *
     * <p>2026-10-28「指标与告警解耦」后，{@code metric_item} 不再存
     * alarm_threshold / alarm_threshold_type / gongdan_threshold 等 6 列，
     * 阈值唯一来源是 {@code alarm_strategy}。这里原先直接选 {@code mi.alarm_threshold}，
     * 列被删后会抛 {@code Unknown column}，导致运营专屏整页 500，故改为子查询。
     *
     * <p>口径与 {@link com.metric.alarm.service.AlarmStrategyQueryService} 完全一致：
     * 只看 {@code strategy_type='metric'}、未停用（status 为 null 或非 0）、该字段有值，
     * 多条时取 {@code id} 最小的一条；无值则返回 null，由前端显示「未配置」。
     * 用相关子查询而非 JOIN，避免一个指标有多条策略时把结果行数放大。
     *
     * @param metricIdColumn 指标 id 的列引用，例如 {@code "mi.id"} 或 {@code "metric_item.id"}
     */
    private static String alarmThresholdExpr(String metricIdColumn){
        return "(SELECT s.alarm_threshold FROM alarm_strategy s"
                + " WHERE s.target_type='metric' AND s.strategy_type='metric'"
                + " AND s.target_id=" + metricIdColumn
                + " AND s.alarm_threshold IS NOT NULL"
                + " AND (s.status IS NULL OR s.status<>0)"
                + " ORDER BY s.id LIMIT 1)";
    }
    public Object configuration(List<DashboardViewConfig> views){
        return Map.of("views",views,"nodes",access.catalog().values(),
            "users",jdbc.queryForList("SELECT id,username,display_name AS displayName FROM sys_user WHERE status=1 ORDER BY id"),
            "roles",jdbc.queryForList("SELECT id,role_name AS roleName FROM sys_role WHERE status=1 ORDER BY id"));
    }
    private record Context(DashboardViewConfig view,DashboardPolicy policy,Map<String,DashboardScope.Node> catalog,Set<String> keys,Set<String> modules,Map<String,Object> params,String audience,int days,TimeRange range){}
    private Context context(String code,int days,Long preview,Long supervisor){return context(code,days,preview,supervisor,null,null);}
    private Context context(String code,int days,Long preview,Long supervisor,String audience){return context(code,days,preview,supervisor,audience,null);}
    private Context context(String code,int days,Long preview,Long supervisor,String audience,TimeRange range){
        // 只有未传自定义区间时才校验 days 仅限 1/7/30；传了区间时 days 仅作为兼容参数，不参与取数。
        if(range==null&&!Set.of(1,7,30).contains(days)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"时间范围仅支持1、7、30天");
        var view=access.requireView(code,preview);var policy=access.policy(view);
        if(policy==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"旧版专屏尚未配置授权，请管理员编辑后保存");
        var catalog=access.catalog();Long user=preview==null?access.userId():preview;
        Set<String> keys=access.scope(view,user,catalog);
        Set<String> modules=new LinkedHashSet<>(policy.modules());
        if("manager".equals(policy.template())){
            for(Long uid:access.activeSupervisors(policy)){
                for(var source:repository.findByStatusOrderBySortOrderAsc(1)){
                    var p=access.policy(source);
                    if(p!=null && access.hasSupervisorGrant(source,uid))
                        modules.addAll(p.modules());
                }
            }
        }
        // Admin configuration preview without a selected identity uses the configured grant union.
        if(preview==null && access.isAdmin() && !"manager".equals(policy.template()))
            keys=DashboardScope.resolve(policy.grants().stream().map(DashboardPolicy.Grant::scope).toList(),catalog);
        if(supervisor!=null){
            if(!"manager".equals(policy.template()) || !access.activeSupervisors(policy).contains(supervisor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            Set<String> individual=new HashSet<>();
            for(var source:repository.findByStatusOrderBySortOrderAsc(1)){
                var p=access.policy(source);
                if(p!=null && access.hasSupervisorGrant(source,supervisor)) individual.addAll(access.scope(source,supervisor,catalog));
            }
            keys.retainAll(individual);
        }
        // 2026-11-17 调整：角色（岗位）不再作为专屏整屏的过滤条件。
        // 原实现会按岗位收窄节点集合，连「闭环管理」的告警/问题/工单也一起被过滤，
        // 相当于把岗位提到了整屏顶层，权限层级过高；现在角色只用于
        // 「健康度观测」页签里的角色卡片（前端按 healthCards 的 audienceName 分组），
        // 这里的 audience 参数仅保留兼容，不再参与取数。
        Map<String,Object> params=new HashMap<>();
        for(String type:List.of("system","category","subcategory","metric")){
            final Set<String> selected=keys;
            List<Long> ids=catalog.values().stream().filter(n->n.type().equals(type)&&selected.contains(n.key())).map(DashboardScope.Node::id).toList();
            params.put(type,ids.isEmpty()?List.of(-1L):ids);
        }
        LocalDateTime now=LocalDateTime.now();
        int effectiveDays=range==null?days:range.days();
        // 自定义区间直接覆盖 params.start/end；不传时保持原来的 now-days ~ now。
        params.put("start",range==null?now.minusDays(days):range.start());params.put("end",range==null?now:range.end());
        return new Context(view,policy,catalog,keys,modules,params,audience,effectiveDays,range);
    }
    /** Scope used by an anonymous link: it is the published view's configured scope, never request parameters. */
    private Context sharedContext(String code,int days){return sharedContext(code,days,null);}
    private Context sharedContext(String code,int days,TimeRange range){
        if(range==null&&!Set.of(1,7,30).contains(days)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"时间范围仅支持1、7、30天");
        var view=repository.findByViewCode(code).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        var policy=access.policy(view);
        if(!Integer.valueOf(1).equals(view.getStatus())||policy==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var catalog=access.catalog(); Set<String> keys;
        if("manager".equals(policy.template())){
            keys=new HashSet<>();
            for(Long uid:access.activeSupervisors(policy)) for(var source:repository.findByStatusOrderBySortOrderAsc(1)) {
                var p=access.policy(source); if(p!=null&&access.hasSupervisorGrant(source,uid)) keys.addAll(access.scope(source,uid,catalog));
            }
        } else keys=DashboardScope.resolve(policy.grants().stream().map(DashboardPolicy.Grant::scope).toList(),catalog);
        Set<String> modules=new LinkedHashSet<>(policy.modules());
        if("manager".equals(policy.template())) for(Long uid:access.activeSupervisors(policy)) for(var source:repository.findByStatusOrderBySortOrderAsc(1)) {
            var p=access.policy(source); if(p!=null&&access.hasSupervisorGrant(source,uid)) modules.addAll(p.modules());
        }
        Map<String,Object> params=new HashMap<>();
        for(String type:List.of("system","category","subcategory","metric")){final Set<String> selected=keys;List<Long> ids=catalog.values().stream().filter(n->n.type().equals(type)&&selected.contains(n.key())).map(DashboardScope.Node::id).toList();params.put(type,ids.isEmpty()?List.of(-1L):ids);}
        LocalDateTime now=LocalDateTime.now();
        int effectiveDays=range==null?days:range.days();
        params.put("start",range==null?now.minusDays(days):range.start());params.put("end",range==null?now:range.end());
        params.put("days",effectiveDays);
        return new Context(view,policy,catalog,keys,modules,params,null,effectiveDays,range);
    }
    private void requireModule(Context c,String module){if(!c.modules.contains(module))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该模块未开放");}
    private String filter(String kind){
        String sub=kind.equals("gongdan")?"":" OR (e.metric_id IS NULL AND EXISTS (SELECT 1 FROM metric_sub_category s WHERE s.id IN (:subcategory) AND s.category_id=e.category_id AND s.sub_category_name=e.sub_category_name))";
        String noSub=kind.equals("gongdan")?"":" AND (e.sub_category_name IS NULL OR e.sub_category_name='')";
        return "(e.metric_id IN (:metric) OR (e.metric_id IS NULL AND e.category_id IN (:category)"+noSub+")"+sub+" OR (e.metric_id IS NULL AND e.category_id IS NULL AND e.system_id IN (:system)"+noSub+"))";
    }
    private record EventSpec(String time,String doneTime,String done,String title,String owner){}
    private EventSpec spec(String kind){return switch(kind){
        case "alarm"->new EventSpec("alarm_time","process_time","process_status=1","alarm_content","responsible_person");
        case "issue"->new EventSpec("register_time","resolve_time","resolve_status='已解决'","issue_description","responsible_person");
        case "gongdan"->new EventSpec("gongdan_time","closed_time","closed_status=1","gongdan_title","current_handler");
        default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效记录类型");};}
    private Map<String,Object> stats(Context c,String kind){
        var s=spec(kind);String where=filter(kind);
        Map<String,Object> result=sql.queryForMap("SELECT COUNT(*) AS total,COALESCE(SUM(NOT ("+s.done+")),0) AS pending,COALESCE(SUM("+s.time+">=:start AND "+s.time+"<=:end),0) AS created,COALESCE(SUM(("+s.done+") AND "+s.doneTime+">=:start AND "+s.doneTime+"<=:end),0) AS completed FROM "+kind+" e WHERE "+where,c.params);
        result.put("trend",sql.queryForList("SELECT DATE("+s.time+") AS day,COUNT(*) AS created FROM "+kind+" e WHERE "+where+" AND "+s.time+">=:start AND "+s.time+"<=:end GROUP BY DATE("+s.time+") ORDER BY day",c.params));
        result.put("completionTrend",sql.queryForList("SELECT DATE("+s.doneTime+") AS day,COUNT(*) AS completed FROM "+kind+" e WHERE "+where+" AND ("+s.done+") AND "+s.doneTime+">=:start AND "+s.doneTime+"<=:end GROUP BY DATE("+s.doneTime+") ORDER BY day",c.params));
        result.put("averageHours",sql.queryForObject("SELECT AVG(TIMESTAMPDIFF(SECOND,"+s.time+","+s.doneTime+"))/3600 FROM "+kind+" e WHERE "+where+" AND ("+s.done+") AND "+s.doneTime+">=:start AND "+s.doneTime+"<=:end AND "+s.doneTime+">="+s.time,c.params,Double.class));
        if(kind.equals("issue")) result.put("timeliness",sql.queryForMap("SELECT COUNT(*) AS due,COALESCE(SUM(resolve_status='已解决' AND resolve_time<=plan_resolve_time),0) AS onTime,COALESCE(SUM(resolve_status<>'已解决' AND plan_resolve_time<:end),0) AS overdue FROM issue e WHERE "+where+" AND plan_resolve_time>=:start AND plan_resolve_time<=:end",c.params));
        if(kind.equals("gongdan")) result.put("closure",sql.queryForMap("SELECT COUNT(*) AS created,COALESCE(SUM(closed_status=1),0) AS closed FROM gongdan e WHERE "+where+" AND gongdan_time>=:start AND gongdan_time<=:end",c.params));
        return result;
    }
    private Object overviewInternal(String code,int days,Long preview,Long supervisor,boolean shared,boolean includeMetrics){
        return overviewInternal(code,days,preview,supervisor,shared,includeMetrics,"all",null,null);
    }

    private Object overviewInternal(String code,int days,Long preview,Long supervisor,boolean shared,boolean includeMetrics,String panel,String audience,TimeRange range){
        Context c=shared?sharedContext(code,days,range):context(code,days,preview,supervisor,audience,range);
        return overviewPanel(c,preview,supervisor,shared,includeMetrics,panel);
    }

    private Map<String,Object> overviewPanel(Context c,Long preview,Long supervisor,boolean shared,boolean includeMetrics,String panel) {
        Map<String,Object> result=new LinkedHashMap<>();
        if (panel.equals("all") || panel.equals("summary")) populateOverviewSummary(c,result,!shared);
        if (panel.equals("summary")) {
            var tabs = sharedTabs(c);
            result.put("tabs", List.copyOf(tabs.keySet()));
            result.put("tabPanels", tabs);
            return result;
        }
        if (panel.equals("data")) {
            if (c.modules.contains("data")) {
                var periodRows = observationReads.periodDataInRange((List<Long>)c.params.get("metric"),
                    (LocalDateTime)c.params.get("start"), (LocalDateTime)c.params.get("end"));
                populateDataSummary(result,periodRows);
                if (shared && !c.modules.contains("health")) removeHealthFields(result.get("dataMetrics"));
            }
            return result;
        }
        if (Set.of("catalog","sources","strategy").contains(panel)) {
            populateMetricPanels(c,result,panel,false);
            if (shared && panel.equals("sources")) {
                result.remove("dataMetrics");
                result.remove("dataSummary");
                if (!c.modules.contains("health")) removeHealthFields(result.get("dataSources"));
            }
            return result;
        }
        if (panel.equals("overview")) {
            if (c.policy.template().equals("leader") && c.modules.contains("events")) {
                for (String kind : List.of("alarm","issue","gongdan")) {
                    var counts = stats(c,kind);
                    Map<String,Object> summary = new LinkedHashMap<>();
                    summary.put("pending",counts.get("pending"));
                    if (kind.equals("issue")) summary.put("timeliness",counts.get("timeliness"));
                    result.put(kind,summary);
                }
            }
            return result;
        }
        result.put("updatedAt",LocalDateTime.now());
        if(includeMetrics) populateMetricPanels(c,result,"all",false);
        if((panel.equals("all") || panel.equals("closure")) && (c.modules.contains("events")||(!shared && c.modules.contains("report")))){
            for(String kind:List.of("alarm","issue","gongdan")) result.put(kind,stats(c,kind));
            result.put("responsibility",sql.queryForList("SELECT COALESCE(NULLIF(responsible_person,''),'未分配') AS person,COUNT(*) AS total,SUM(resolve_status<>'已解决') AS pending,SUM(resolve_status<>'已解决' AND plan_resolve_time<:end) AS overdue,SUM(resolve_status='已解决' AND resolve_time>=:start AND resolve_time<=:end) AS completed FROM issue e WHERE "+filter("issue")+" GROUP BY responsible_person ORDER BY overdue DESC,pending DESC LIMIT 30",c.params));
            result.put("focusIssues",sql.queryForList("SELECT e.id,e.issue_description AS issueDescription,e.responsible_person AS owner,e.register_time AS registerTime,e.plan_resolve_time AS dueTime,e.resolve_status AS status,e.system_name AS systemName,e.category_name AS categoryName,e.metric_name AS metricName FROM issue e WHERE "+filter("issue")+" AND e.tag_status=1 AND e.resolve_status<>'已解决' ORDER BY e.register_time DESC,e.id DESC LIMIT 10",c.params));
            // 闭环卡片同时给出平台全量口径（platformClosure）。工单/问题/告警大多挂在某一两个大类下，
            // 授权范围只到「系统运行」这类专屏时本屏几乎为空，只显示范围数会被误读成「平台没有闭环」（用户 2026-11-25 反馈）。
            Map<String,Object> platformClosure=new LinkedHashMap<>();
            Map<String,Object> noParams=new HashMap<>();
            platformClosure.put("alarmPending",sql.queryForObject("SELECT COALESCE(SUM(NOT (process_status=1)),0) FROM alarm",noParams,Long.class));
            platformClosure.put("issuePending",sql.queryForObject("SELECT COALESCE(SUM(resolve_status<>'已解决'),0) FROM issue",noParams,Long.class));
            platformClosure.put("issueResolved",sql.queryForObject("SELECT COALESCE(SUM(resolve_status='已解决'),0) FROM issue",noParams,Long.class));
            platformClosure.put("gongdanPending",sql.queryForObject("SELECT COALESCE(SUM(NOT (closed_status=1)),0) FROM gongdan",noParams,Long.class));
            platformClosure.put("gongdanCreated",sql.queryForObject("SELECT COUNT(*) FROM gongdan",noParams,Long.class));
            platformClosure.put("gongdanClosed",sql.queryForObject("SELECT COALESCE(SUM(closed_status=1),0) FROM gongdan",noParams,Long.class));
            platformClosure.put("gongdanLatest",sql.queryForObject("SELECT DATE_FORMAT(MAX(gongdan_time),'%Y-%m-%d %H:%i:%s') FROM gongdan",noParams,String.class));
            result.put("platformClosure",platformClosure);
        }
        if(c.policy.template().equals("manager") && Set.of("all","supervisors","team").contains(panel)){
            List<Map<String,Object>> people=new ArrayList<>();
            for(Long uid:access.activeSupervisors(c.policy)) people.addAll(jdbc.queryForList("SELECT id,username,display_name AS displayName,last_login_time AS lastLogin FROM sys_user WHERE id=?",uid));
            result.put("supervisors",people);
            if((panel.equals("all") || panel.equals("supervisors")) && c.modules.contains("events")){
                List<Map<String,Object>> summaries=new ArrayList<>();
                for(var person:people){
                    Long uid=((Number)person.get("id")).longValue();
                    if(supervisor!=null && !supervisor.equals(uid)) continue;
                    Context individual=shared?sharedContextForSupervisor(c,uid):context(c.view.getViewCode(),c.days(),preview,uid,c.audience(),c.range());
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("id",uid);
                    row.put("displayName",person.get("displayName"));
                    row.put("username",person.get("username"));
                    row.put("metricCount",individual.params.get("metric") instanceof List<?> ids && !ids.equals(List.of(-1L)) ? ids.size() : 0);
                    for(String kind:List.of("alarm","issue","gongdan")){
                        EventSpec spec=spec(kind);
                        row.put(kind+"Pending",sql.queryForObject("SELECT COALESCE(SUM(NOT ("+spec.done+")),0) FROM "+kind+" e WHERE "+filter(kind),individual.params,Long.class));
                    }
                    row.put("issueOverdue",sql.queryForObject("SELECT COALESCE(SUM(resolve_status<>'已解决' AND plan_resolve_time<:end),0) FROM issue e WHERE "+filter("issue"),individual.params,Long.class));
                    row.put("gongdanCreated",sql.queryForObject("SELECT COUNT(*) FROM gongdan e WHERE "+filter("gongdan")+" AND gongdan_time>=:start AND gongdan_time<=:end",individual.params,Long.class));
                    row.put("gongdanClosed",sql.queryForObject("SELECT COALESCE(SUM(closed_status=1),0) FROM gongdan e WHERE "+filter("gongdan")+" AND gongdan_time>=:start AND gongdan_time<=:end",individual.params,Long.class));
                    summaries.add(row);
                }
                result.put("supervisorSummary",summaries);
            }
            if((panel.equals("all") || panel.equals("team")) && c.modules.contains("team")){
                for(var p:people){
                    String username=p.get("username").toString();
                    p.put("usage",jdbc.queryForMap("SELECT COUNT(*) AS sessions,MAX(login_time) AS lastSession FROM user_session WHERE username=? AND login_time>=? AND login_time<=?",username,c.params.get("start"),c.params.get("end")));
                    p.put("operations",jdbc.queryForObject("SELECT COUNT(*) FROM operation_log WHERE operator=? AND create_time>=? AND create_time<=?",Long.class,username,c.params.get("start"),c.params.get("end")));
                }
                result.put("team",people);
            }
        }
        if(Set.of("all","operations").contains(panel)&&c.policy.template().equals("operator")&&c.modules.contains("operations")) result.put("operations",operations(c));
        return result;
    }
    private void populateOverviewSummary(Context c, Map<String,Object> result, boolean includePlatformMetadata) {
        result.put("name",c.view.getViewName());
        result.put("template",c.policy.template());
        result.put("modules",c.modules);
        result.put("layoutMode",c.policy.layoutMode());
        result.put("tabs",c.policy.tabs());
        if (includePlatformMetadata) {
            result.put("visibilitySummary",access.visibilitySummary(c.view));
            result.put("nodes",visibleNodesWithSystemAncestors(c));
        }
        Map<String,Long> counts=new LinkedHashMap<>();
        // 「接入系统」按可见节点实际归属的系统去重统计。
        // 主管/个人授权常常只授到 category/metric 级，keys 里没有 system 节点，
        // 老口径按「keys 里有没有 system 节点」数出来恒为 0，因此改为按节点 systemId 去重。
        counts.put("system",c.catalog.values().stream()
            .filter(n->c.keys.contains(n.key()))
            .map(DashboardScope.Node::systemId).filter(Objects::nonNull).distinct().count());
        for(String type:List.of("category","subcategory","metric")) counts.put(type,c.catalog.values().stream().filter(n->n.type().equals(type)&&c.keys.contains(n.key())).count());
        result.put("counts",counts);result.put("updatedAt",LocalDateTime.now());
        // 「指标基线」同时给出平台全量（platformCounts）与本屏授权范围（counts）。
        // 主管/个人授权通常只有某个大类下的少量指标，只显示范围数会被误读成「平台就这么多」（用户 2026-11-25 反馈）。
        Map<String,Long> platformCounts=new LinkedHashMap<>();
        platformCounts.put("system",c.catalog.values().stream().filter(n->n.type().equals("system")).count());
        for(String type:List.of("category","subcategory","metric")) platformCounts.put(type,c.catalog.values().stream().filter(n->n.type().equals(type)).count());
        result.put("platformCounts",platformCounts);
        // 角色（岗位）维度已收敛到「健康度观测」页签的卡片（前端按 healthCards 分组），
        // 不再在概览里下发岗位字典/分岗位汇总，避免顶层过滤整屏。
    }

    /**
     * 平台内页面下发的节点列表：授权节点 + 其祖先系统节点。
     *
     * <p>主管/个人授权通常是 category/subcategory/metric 级，nodes 里没有 system 节点，
     * 前端「系统」筛选下拉会是空的。这里只补系统节点的名称/ID 供筛选展示，
     * <b>不加入 c.keys</b>：所有取数 SQL 仍按 keys 过滤，不扩大授权范围；
     * 共享页（includePlatformMetadata=false）保持不下发 nodes。
     */
    private List<DashboardScope.Node> visibleNodesWithSystemAncestors(Context c) {
        Map<String,DashboardScope.Node> nodes=new LinkedHashMap<>();
        c.catalog.values().stream().filter(n->c.keys.contains(n.key())).forEach(n->nodes.put(n.key(),n));
        Set<String> systemKeys=new LinkedHashSet<>();
        nodes.values().forEach(n->{ if(n.systemId()!=null) systemKeys.add("system:"+n.systemId()); });
        for(String key:systemKeys){
            var system=c.catalog.get(key);
            if(system!=null) nodes.putIfAbsent(key,system);
        }
        return new ArrayList<>(nodes.values());
    }

    private void removeHealthFields(Object data) {
        if (data instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?,?> metric) {
                    metric.keySet().removeAll(Set.of("healthScore","healthStatus","latestCalculationTime","trend"));
                }
            }
        }
    }

    private Map<String,String> sharedTabs(Context c) {
        Map<String,String> tabs = new LinkedHashMap<>();
        var allowedTabs = DashboardPolicy.allowedTabs(c.policy.template());
        for (String tab : c.policy.tabs()) {
            if (!allowedTabs.contains(tab)) continue;
            String requiredModule = switch (tab) {
                case "health-observation", "risk" -> "health";
                case "situation", "outcome", "events", "supervisors" -> "events";
                case "data", "team", "operations" -> tab;
                default -> null;
            };
            if (requiredModule != null && !c.modules.contains(requiredModule)) continue;
            String panel = switch (tab) {
                case "overview" -> c.policy.template().equals("supervisor") ? "closure" : "overview";
                case "situation", "outcome", "events" -> "closure";
                case "health-observation", "risk" -> "health";
                default -> tab;
            };
            tabs.put(tab,panel);
        }
        return tabs;
    }

    private void requireSharedPanel(Context c, String panel) {
        if (!sharedTabs(c).containsValue(panel)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该分享未开放此栏目");
        }
    }

    public Object sharePanel(String code, int days, String panel) { return sharePanel(code,days,panel,null); }
    public Object sharePanel(String code, int days, String panel, TimeRange range) {
        Context c = sharedContext(code,days,range);
        if (panel.equals("summary")) return overviewPanel(c,null,null,true,false,"summary");
        var tabs = sharedTabs(c);
        if (panel.equals("all")) {
            // PC 分享页「一页全览」一次性拉全部页签，避免它按页签逐个请求。
            Map<String,Object> result=new LinkedHashMap<>();
            for (String target : new LinkedHashSet<>(tabs.values())) {
                if (target.equals("health")) continue;
                result.putAll(overviewPanel(c,null,null,true,false,target));
            }
            if (c.modules.contains("health")) result.put("healthCards",healthCards(c));
            return result;
        }
        // 页面传的是页签 key（overview/catalog/...），后端面板名可能是 closure 等，先映射再校验。
        String mapped = tabs.getOrDefault(panel,panel);
        requireSharedPanel(c,mapped);
        if (mapped.equals("health")) return healthCards(c);
        Map<String,Object> result=new LinkedHashMap<>(overviewPanel(c,null,null,true,false,mapped));
        // 「指标与健康」「数据来源」两个页签都直接渲染指标卡，顺带返回健康度卡片省一次请求。
        if(Set.of("data","sources").contains(mapped) && c.modules.contains("health") && !result.containsKey("healthCards"))
            result.put("healthCards",healthCards(c));
        return result;
    }

    private void populateMetricPanels(Context c,Map<String,Object> result,String panel,boolean includeHealthCards) {
        List<Map<String,Object>> latestRows = null;
        List<Map<String,Object>> healthRows = null;
        if(Set.of("supervisor","manager").contains(c.policy.template())) {
            if(panel.equals("all") || panel.equals("catalog") || panel.equals("data")) result.put("catalogMetrics", sql.queryForList("SELECT mi.id,si.system_name AS systemName,mc.category_name AS categoryName,mi.sub_category_name AS subCategoryName,mi.metric_name AS metricName,mi.metric_code AS metricCode,mi.metric_type AS metricType,mi.metric_definition AS metricDefinition,mi.unit,mi.collect_frequency AS collectFrequency,"+alarmThresholdExpr("mi.id")+" AS alarmThreshold,mi.status FROM metric_item mi JOIN metric_category mc ON mc.id=mi.category_id JOIN system_info si ON si.id=mc.system_id WHERE mi.id IN (:metric) ORDER BY si.system_name,mc.category_name,mi.sub_category_name,mi.metric_name",c.params));
            if(panel.equals("all") || panel.equals("sources") || panel.equals("data")) latestRows = observationReads.latestWithPeriodStatistics((List<Long>)c.params.get("metric"),(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end"));
            if(latestRows!=null) attachExempt(latestRows);
            if(panel.equals("all") || panel.equals("sources") || panel.equals("data")) result.put("dataSources", readableMetrics(latestRows));
            if(panel.equals("all") || panel.equals("strategy")) result.put("strategies", sql.queryForList("SELECT s.id,s.strategy_name AS strategyName,s.strategy_type AS strategyType,s.target_name AS targetName,mi.metric_name AS metricName,s.alarm_rules AS alarmRules,s.gongdan_rules AS gongdanRules,s.alarm_enabled AS alarmEnabled,s.alarm_condition AS alarmCondition,s.alarm_threshold AS alarmThreshold,s.alarm_consecutive_times AS alarmConsecutiveTimes,s.gongdan_enabled AS gongdanEnabled,s.gongdan_condition AS gongdanCondition,s.gongdan_threshold AS gongdanThreshold,s.gongdan_consecutive_times AS gongdanConsecutiveTimes,s.status,s.update_time AS updateTime FROM alarm_strategy s LEFT JOIN metric_item mi ON mi.id=s.target_id WHERE s.target_type='metric' AND s.target_id IN (:metric) ORDER BY s.status DESC,s.update_time DESC",c.params));
        }
        // The data and source tabs share the same expensive observation read. Return both
        // projections together so switching between them does not scan the history twice.
        if(c.modules.contains("data") && (panel.equals("all") || panel.equals("data") || panel.equals("sources"))) {
            var periodRows = latestRows == null
                ? observationReads.periodData((List<Long>)c.params.get("metric"),(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end"))
                : observationReads.periodDataFromLatest(latestRows,(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end"));
            healthRows = latestRows == null ? periodRows : latestRows;
            populateDataSummary(result,periodRows);
        }
        if(includeHealthCards && c.modules.contains("health") && healthRows!=null
            && (panel.equals("all") || panel.equals("data") || panel.equals("sources"))) {
            result.put("healthCards",healthCardsForRows(c,healthRows));
        }
    }

    private void populateDataSummary(Map<String,Object> result, List<Map<String,Object>> periodRows) {
        attachExempt(periodRows);
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("coveredMetrics",periodRows.stream().filter(row -> ((Number)row.get("validRecords")).longValue()>0).count());
        summary.put("records",periodRows.stream().mapToLong(row -> ((Number)row.get("records")).longValue()).sum());
        summary.put("latest",periodRows.stream().map(row -> (String)row.get("latestDataTime")).filter(Objects::nonNull).max(String::compareTo).orElse(null));
        result.put("dataSummary",summary);
        result.put("dataMetrics",readableMetrics(periodRows));
    }

    public Object metricPanel(String code,int days,Long preview,Long supervisor,String panel) {
        return metricPanel(code,days,preview,supervisor,panel,null,null);
    }
    public Object metricPanel(String code,int days,Long preview,Long supervisor,String panel,String audience) {
        return metricPanel(code,days,preview,supervisor,panel,audience,null);
    }
    public Object metricPanel(String code,int days,Long preview,Long supervisor,String panel,String audience,TimeRange range) {
        if(!Set.of("all","catalog","sources","strategy","data").contains(panel))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效指标面板");
        Context c=context(code,days,preview,supervisor,audience,range);
        Map<String,Object> result=new LinkedHashMap<>();
        populateMetricPanels(c,result,panel,true);
        return result;
    }

    private Context sharedContextForSupervisor(Context all,Long supervisor){
        var policy=all.policy; if(!"manager".equals(policy.template())||!access.activeSupervisors(policy).contains(supervisor)) return all;
        Set<String> keys=new HashSet<>(); for(var source:repository.findByStatusOrderBySortOrderAsc(1)){var p=access.policy(source);if(p!=null&&access.hasSupervisorGrant(source,supervisor))keys.addAll(access.scope(source,supervisor,all.catalog));}
        Map<String,Object> params=new HashMap<>(all.params);for(String type:List.of("system","category","subcategory","metric")){final Set<String> selected=keys;List<Long> ids=all.catalog.values().stream().filter(n->n.type().equals(type)&&selected.contains(n.key())).map(DashboardScope.Node::id).toList();params.put(type,ids.isEmpty()?List.of(-1L):ids);}return new Context(all.view,policy,all.catalog,keys,all.modules,params,all.audience,all.days,all.range);
    }
    public Object overview(String code,int days,Long preview,Long supervisor){return overview(code,days,preview,supervisor,true,null,null);}
    public Object overview(String code,int days,Long preview,Long supervisor,boolean includeMetrics) {
        return overview(code,days,preview,supervisor,includeMetrics,null,null);
    }
    public Object overview(String code,int days,Long preview,Long supervisor,boolean includeMetrics,String audience) {
        return overview(code,days,preview,supervisor,includeMetrics,audience,null);
    }
    public Object overview(String code,int days,Long preview,Long supervisor,boolean includeMetrics,String audience,TimeRange range) {
        return overviewInternal(code,days,preview,supervisor,false,includeMetrics,"all",audience,range);
    }
    public Object shareOverview(String code,int days) { return shareOverview(code,days,null); }
    public Object shareOverview(String code,int days,TimeRange range) {
        Context c = sharedContext(code,days,range);
        var result = overviewPanel(c,null,null,true,false,"summary");
        for (String panel : new LinkedHashSet<>(sharedTabs(c).values())) {
            if (!panel.equals("health")) result.putAll(overviewPanel(c,null,null,true,false,panel));
        }
        // PC 分享页复用平台内专屏页面：筛选框、健康度观测都依赖 nodes；
        // nodes 只保留分享授权范围内的节点，不会因此扩大可见范围。
        result.put("nodes",c.catalog.values().stream().filter(n->c.keys.contains(n.key())).toList());
        return result;
    }
    public Object health(String code,String key,int days,Long preview,Long supervisor){
        return health(code,key,days,preview,supervisor,null);
    }
    public Object health(String code,String key,int days,Long preview,Long supervisor,TimeRange range){
        Context c=context(code,days,preview,supervisor,null,range);
        return healthResult(c,key);
    }
    /** 匿名分享链接的节点健康度：范围固定为已发布专屏的授权范围。 */
    public Object shareHealth(String code,String key,int days){
        return shareHealth(code,key,days,null);
    }
    public Object shareHealth(String code,String key,int days,TimeRange range){
        return healthResult(sharedContext(code,days,range),key);
    }
    private Map<String,Object> healthResult(Context c,String key){
        requireModule(c,"health");
        if(!c.keys.contains(key))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该节点未获授权");
        // 快照聚合只有 1d/7d/30d 三档；自定义区间用按窗口长度换算的等价 days，避免越界。
        int days=c.days();
        var n=c.catalog.get(key);String range=days==1?"1d":days==7?"7d":"30d";
        Map<String,Object> result=new LinkedHashMap<>();result.put("node",n);
        // Historical category/subcategory snapshots have no reliable system FK: ambiguous names fail closed.
        if((n.type().equals("category")||n.type().equals("subcategory")) && c.catalog.values().stream().filter(x->x.type().equals("category")&&Objects.equals(x.label(),n.categoryName())).count()!=1){
            result.put("data",List.of());result.put("notice","历史健康度按名称存储，存在跨系统重名，无法安全归属，暂不展示");return result;
        }
        Object trend=switch(n.type()){
            case "system"->snapshots.getSystemTrend(n.id(),range);
            case "category"->snapshots.getCategoryTrend(n.categoryName(),range);
            case "subcategory"->snapshots.getSubcategoryTrend(n.categoryName(),n.subCategoryName(),range);
            default->snapshots.getMetricHealthTrend(n.id(),range);
        };
        result.put("data",trend);
        if(n.type().equals("metric")){
            var values=observationReads.trend(n.id(),(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end"));
            result.put("values",values.get("data"));
            result.put("valueNotice",values.get("notice"));
            // 指标详情/专屏图表要同时画「指标值 + 健康度」两条线：trend() 的采集点
            // healthScore 固定为 null，健康度必须另取 metric_health_history 区间记录，
            // 口径与 shareMetricDetail 完全一致（同一时间窗）。
            result.put("healthTrend",observationReads.healthTrend(n.id(),(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end")));
            // 以前用"是否采集类指标"来推断"未配置健康度规则"，采集类指标明明是配了规则、
            // 也有健康度分数的，却一直显示这句提示，容易被当成没数据。这里改成真实查规则。
            boolean hasHealthRule=!sql.queryForList(
                    "SELECT 1 FROM metric_health_rule WHERE metric_id=:metricId AND (status IS NULL OR status<>0)",
                    Map.of("metricId",n.id())).isEmpty();
            if(!hasHealthRule) result.put("notice","未配置健康度规则；指标值按实际采集数据展示");
            result.put("definition",metricDefinition(n.id()));
        }
        return result;
    }
    private static final List<String> DATA_CATEGORY_ORDER = List.of("对外部客户","合规管理","对内部人员","运行效率","计划执行","满意度及投诉服务体验","资源投入","业务价值");
    public Object dataMeta(String code,int days,Long preview,Long supervisor){return dataMeta(code,days,preview,supervisor,null,null);}
    public Object dataMeta(String code,int days,Long preview,Long supervisor,String audience){return dataMeta(code,days,preview,supervisor,audience,null);}
    public Object dataMeta(String code,int days,Long preview,Long supervisor,String audience,TimeRange range){
        return dataMetaResult(context(code,days,preview,supervisor,audience,range));
    }
    /** 匿名分享链接的明细维度目录。 */
    public Object shareDataMeta(String code,int days){return shareDataMeta(code,days,null);}
    public Object shareDataMeta(String code,int days,TimeRange range){return dataMetaResult(sharedContext(code,days,range));}
    private Map<String,Object> dataMetaResult(Context c){
        requireModule(c,"data");
        List<Map<String,Object>> rows=sql.queryForList("SELECT si.system_name AS systemName,mc.category_name AS categoryName,mi.sub_category_name AS subCategoryName,COUNT(*) AS metricCount FROM metric_item mi JOIN metric_category mc ON mc.id=mi.category_id JOIN system_info si ON si.id=mc.system_id WHERE mi.id IN (:metric) GROUP BY si.system_name,mc.category_name,mi.sub_category_name",c.params);
        Map<String,Map<String,Object>> dimensions=new LinkedHashMap<>();
        for(Map<String,Object> row:rows){
            String category=String.valueOf(row.get("categoryName"));String sub=String.valueOf(row.get("subCategoryName"));
            Map<String,Object> dim=dimensions.computeIfAbsent(category,k->{Map<String,Object> m=new LinkedHashMap<>();m.put("name",category);m.put("systems",new LinkedHashSet<String>());m.put("subcategories",new LinkedHashMap<String,Map<String,Object>>());return m;});
            ((Set<String>)dim.get("systems")).add(String.valueOf(row.get("systemName")));
            Map<String,Map<String,Object>> subs=(Map<String,Map<String,Object>>)dim.get("subcategories");
            subs.computeIfAbsent(sub,k->{Map<String,Object> m=new LinkedHashMap<>();m.put("name",sub);m.put("metricCount",0);return m;}).put("metricCount",((Number)subs.computeIfAbsent(sub,k->new LinkedHashMap<>()).getOrDefault("metricCount",0)).longValue()+((Number)row.get("metricCount")).longValue());
        }
        List<Map<String,Object>> list=new ArrayList<>();
        for(Map<String,Object> dim:dimensions.values()){
            dim.put("systems",new ArrayList<>((Set<String>)dim.get("systems")));
            dim.put("subcategories",new ArrayList<>(((Map<String,Map<String,Object>>)dim.get("subcategories")).values()));
            list.add(dim);
        }
        list.sort(Comparator.comparingInt(x->{int i=DATA_CATEGORY_ORDER.indexOf(String.valueOf(x.get("name")));return i<0?99:i;}));
        Map<String,Object> result=new LinkedHashMap<>();result.put("dimensions",list);result.put("metricCount",((List<?>)c.params.get("metric")).size());return result;
    }
    public Object dataRows(String code,String category,String subCategory,int page,int size,Long preview,Long supervisor){return dataRows(code,category,subCategory,page,size,preview,supervisor,null,null);}
    public Object dataRows(String code,String category,String subCategory,int page,int size,Long preview,Long supervisor,String audience){return dataRows(code,category,subCategory,page,size,preview,supervisor,audience,null);}
    public Object dataRows(String code,String category,String subCategory,int page,int size,Long preview,Long supervisor,String audience,TimeRange range){
        // 明细行本身不分时间窗过滤；这里仍解析/透传窗口只为与其它面板保持同一套校验与权限上下文。
        return dataRowsResult(context(code,7,preview,supervisor,audience,range),category,subCategory,page,size);
    }
    /** 匿名分享链接的原始明细行（表名、分类仍受分享授权范围限制）。 */
    public Object shareDataRows(String code,String category,String subCategory,int page,int size){return shareDataRows(code,category,subCategory,page,size,null);}
    public Object shareDataRows(String code,String category,String subCategory,int page,int size,TimeRange range){
        return dataRowsResult(sharedContext(code,7,range),category,subCategory,page,size);
    }
    private Map<String,Object> dataRowsResult(Context c,String category,String subCategory,int page,int size){
        requireModule(c,"data");
        if(page<0||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数不合法");
        Map<String,Object> params=new HashMap<>(c.params);params.put("categoryName",category);params.put("subCategoryName",subCategory);
        List<Map<String,Object>> allowed=sql.queryForList("SELECT mi.id FROM metric_item mi JOIN metric_category mc ON mc.id=mi.category_id WHERE mi.id IN (:metric) AND mc.category_name=:categoryName AND mi.sub_category_name=:subCategoryName LIMIT 1",params);
        if(allowed.isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该数据分类未获授权");
        TableSpec spec=tableSpec(category,subCategory);
        Map<String,Object> result=new LinkedHashMap<>();result.put("categoryName",category);result.put("subCategoryName",subCategory);
        if(spec==null){result.put("rows",List.of());result.put("total",0L);result.put("notice","该维度未配置原始明细表，可在指标与健康中查看指标数据");return result;}
        String where=spec.where==null||spec.where.isBlank()?"":" WHERE "+spec.where;
        Object[] args=spec.args.toArray();
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+spec.table+where,Long.class,args);
        List<Object> dataArgs=new ArrayList<>(spec.args);dataArgs.add(size);dataArgs.add(page*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM "+spec.table+where+" ORDER BY id DESC LIMIT ? OFFSET ?",dataArgs.toArray());
        result.put("rows",rows);result.put("total",total==null?0:total);result.put("table",spec.table);return result;
    }
    private static final class TableSpec { final String table;final String where;final List<Object> args;TableSpec(String table,String where,List<Object> args){this.table=table;this.where=where;this.args=args;} }
    private TableSpec tableSpec(String category,String sub){
        if("合规管理".equals(category)&&("月数据清单".equals(sub)||"系统稽核".equals(sub)))return new TableSpec("system_jhd",null,List.of());
        if("合规管理".equals(category)&&"工单明细".equals(sub))return new TableSpec("system_jhd_detail",null,List.of());
        if("对内部人员".equals(category)&&"查询类".equals(sub))return new TableSpec("inner_query",null,List.of());
        if("对内部人员".equals(category)&&"办理类-单业务".equals(sub))return new TableSpec("inner_step_response","sub_category='crm'",List.of());
        if("对内部人员".equals(category)&&"办理类-融合业务".equals(sub))return new TableSpec("inner_step_response","sub_category='chbn'",List.of());
        if("运行效率".equals(category)&&"系统性能".equals(sub))return new TableSpec("metric_system_run_summary",null,List.of());
        if("运行效率".equals(category)&&"系统异常".equals(sub))return new TableSpec("metric_error_interface",null,List.of());
        if("运行效率".equals(category)&&sub!=null&&sub.startsWith("运行效率-"))return new TableSpec("metric_system_run_summary",null,List.of());
        return null;
    }
    private Object healthCardsInternal(String code,int days,Long preview,Long supervisor,boolean shared){
        return healthCardsInternal(code,days,preview,supervisor,shared,null,null);
    }
    private Object healthCardsInternal(String code,int days,Long preview,Long supervisor,boolean shared,String audience){
        return healthCardsInternal(code,days,preview,supervisor,shared,audience,null);
    }
    private Object healthCardsInternal(String code,int days,Long preview,Long supervisor,boolean shared,String audience,TimeRange range){
        Context c=shared?sharedContext(code,days,range):context(code,days,preview,supervisor,audience,range);requireModule(c,"health");
        return healthCards(c);
    }
    private Object healthCards(Context c) {
        List<Long> ids=(List<Long>)c.params.get("metric");
        var rows=observationReads.latest(ids,(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end"));
        return healthCardsForRows(c,rows);
    }
    private Object healthCardsForRows(Context c,List<Map<String,Object>> latestRows) {
        List<Map<String,Object>> rows=new ArrayList<>();
        latestRows.forEach(row -> rows.add(new LinkedHashMap<>(row)));
        rows.sort(Comparator.comparing(row -> row.get("healthScore")==null?Integer.MAX_VALUE:((Number)row.get("healthScore")).intValue()));
        // 采集类指标（sourceKind=collection）在统一取数口里健康度为空，因为那里坚持
        // 「采集记录与健康度不混用」。但专屏的「指标与健康」要看的正是健康度本身，
        // 健康度来自 metric_health_history，与值来自哪里无关，这里补一次最新分数，
        // 否则成员卡片绝大多数显示「待计算」，人员维度的健康总览看起来是空的。
        fillMissingHealth(c,rows);
        // 带上每张卡的「面向人员」岗位，供健康度观测页签里的角色卡片分组
        attachAudience(rows);
        // 带上白名单标记，专屏「指标与健康」按它隐藏不计分指标
        attachExempt(rows);
        // 补「当前时间窗内采样点均值」：健康度观测页的层级分数用它，
        // 避免只拿最新一次健康度（最新快照常只有部分指标，容易算成 100 分）。
        attachWindowHealthAverage(c,rows);
        Map<Long,List<Map<String,Object>>> trends=new HashMap<>();
        List<Long> trendIds=rows.stream().filter(row -> row.get("healthScore")!=null)
            .map(row -> ((Number)row.get("metricId")).longValue()).toList();
        if(!trendIds.isEmpty()) {
            Map<String,Object> trendParams=new HashMap<>(c.params);
            trendParams.put("metric",trendIds);
            for(Map<String,Object> row:sql.queryForList("""
                WITH ranked AS (
                  SELECT id,metric_id,health_score,record_time,
                    ROW_NUMBER() OVER(PARTITION BY metric_id ORDER BY record_time DESC,id DESC) AS point_rank
                  FROM metric_health_history
                  WHERE metric_id IN (:metric) AND record_time>=:start AND record_time<=:end
                )
                SELECT metric_id AS metricId,health_score AS healthScore,record_time AS recordTime
                FROM ranked WHERE point_rank<=12 ORDER BY record_time DESC,id DESC
                """,trendParams)) {
                Long id=((Number)row.get("metricId")).longValue();
                trends.computeIfAbsent(id,key -> new ArrayList<>()).add(row);
            }
        }
        rows.forEach(row->{Long id=((Number)row.get("metricId")).longValue();List<Map<String,Object>> trend=new ArrayList<>(trends.getOrDefault(id,List.of()));Collections.reverse(trend);row.put("trend",trend);});
        return Map.of("rows",readableMetrics(rows));
    }

    /**
     * 补「当前时间窗内的健康度均值」。
     *
     * <p>专屏健康度观测页的层级分数（系统/大类/小类）要与区间趋势曲线同口径：
     * 取该层可见指标在当前时间窗内 metric_health_history 全部采样点的
     * {@code SUM(health_score)/COUNT(health_score)}，均值保留两位小数返回。
     *
     * <p>为什么不复用卡片里已有的 trend：那张 trend 每个指标只取最近 12 个点，
     * 30 天窗口下用它求平均会明显失真，所以后端直接按 params.start/end 做全量 AVG。
     * {@code windowHealthSamples} 一并返回，前端聚合系统/大类/小类时按采样点数加权，
     * 等价于「该层全部采样点求平均」而不是「各指标均值的简单平均」。
     */
    private void attachWindowHealthAverage(Context c,List<Map<String,Object>> rows) {
        if(rows==null||rows.isEmpty()) return;
        List<Long> ids=rows.stream().filter(row -> row.get("metricId")!=null)
            .map(row -> ((Number)row.get("metricId")).longValue()).distinct().toList();
        if(ids.isEmpty()) return;
        Map<String,Object> params=new HashMap<>(c.params);
        params.put("metric",ids);
        Map<Long,Map<String,Object>> averages=new HashMap<>();
        for(Map<String,Object> row:sql.queryForList("""
            SELECT metric_id AS metricId,AVG(health_score) AS average,COUNT(health_score) AS samples
            FROM metric_health_history
            WHERE metric_id IN (:metric) AND record_time>=:start AND record_time<=:end AND health_score IS NOT NULL
            GROUP BY metric_id
            """,params)) averages.put(((Number)row.get("metricId")).longValue(),row);
        for(Map<String,Object> row:rows) {
            Object metricId=row.get("metricId");
            if(metricId==null) continue;
            Map<String,Object> average=averages.get(((Number)metricId).longValue());
            if(average==null||average.get("average")==null) continue;
            // 均值保留两位小数（前端做层级加权时误差可忽略）；samples 只数有分数的采样点，
            // 不能把 health_score 为 null 的记录算进分母，否则区间均分会被拉低。
            row.put("windowHealthScore",Math.round(((Number)average.get("average")).doubleValue()*100)/100.0);
            row.put("windowHealthSamples",((Number)average.get("samples")).intValue());
        }
    }

    /**
     * 给健康度卡片补「面向人员」（岗位）信息。
     *
     * <p>角色维度只在健康度观测页签的小卡片里用：前端按 audienceName 分组，
     * 卡片范围就是本专屏已授权的 healthCards，不额外放开数据。
     */
    private void attachAudience(List<Map<String,Object>> rows) {
        List<Long> ids=rows.stream().map(row -> ((Number)row.get("metricId")).longValue()).distinct().toList();
        if(ids.isEmpty()) return;
        Map<Long,Map<String,Object>> metas=new HashMap<>();
        for(Map<String,Object> meta:sql.queryForList("""
            SELECT i.id AS metricId,i.audience_code AS audienceCode,a.audience_name AS audienceName
            FROM metric_item i LEFT JOIN metric_audience a ON a.audience_code=i.audience_code AND a.status=1
            WHERE i.id IN (:ids)
            """,Map.of("ids",ids))) metas.put(((Number)meta.get("metricId")).longValue(),meta);
        for(Map<String,Object> row:rows) {
            Map<String,Object> meta=metas.get(((Number)row.get("metricId")).longValue());
            // 停用岗位（a.status<>1）在这里取不到名称：角色维度直接隐藏该指标，
            // 但指标本身仍参与系统/大类健康度。重新启用后自动恢复角色归属。
            row.put("audienceCode",meta==null?null:meta.get("audienceCode"));
            row.put("audienceName",meta==null?null:meta.get("audienceName"));
        }
    }

    /**
     * 给健康度为空的行补最新健康度（来自 metric_health_history）。
     *
     * <p>只补展示字段，不覆盖指标值；健康度历史为空的指标保持「待计算」。
     */
    private void fillMissingHealth(Context c,List<Map<String,Object>> rows) {
        List<Long> missing=rows.stream().filter(row -> row.get("healthScore")==null)
            .map(row -> ((Number)row.get("metricId")).longValue()).distinct().toList();
        if(missing.isEmpty()) return;
        Map<String,Object> params=new HashMap<>(c.params);
        params.put("metric",missing);
        Map<Long,Map<String,Object>> latest=new HashMap<>();
        for(Map<String,Object> row:sql.queryForList("""
            WITH ranked AS (
              SELECT metric_id,health_score,record_time,
                ROW_NUMBER() OVER(PARTITION BY metric_id ORDER BY record_time DESC,id DESC) AS rn
              FROM metric_health_history WHERE metric_id IN (:metric) AND record_time>=:start AND record_time<=:end
            ) SELECT metric_id AS metricId,health_score AS healthScore,record_time AS recordTime FROM ranked WHERE rn=1
            """,params)) latest.put(((Number)row.get("metricId")).longValue(),row);
        for(Map<String,Object> row:rows) {
            if(row.get("healthScore")!=null) continue;
            Map<String,Object> health=latest.get(((Number)row.get("metricId")).longValue());
            if(health==null) continue;
            row.put("healthScore",health.get("healthScore"));
            row.put("healthRecordTime",health.get("recordTime"));
            row.put("latestCalculationTime",health.get("recordTime"));
            row.put("healthStatus","已有健康度记录");
        }
    }
    public Object healthCards(String code,int days,Long preview,Long supervisor){return healthCards(code,days,preview,supervisor,null,null);}
    public Object healthCards(String code,int days,Long preview,Long supervisor,String audience){return healthCards(code,days,preview,supervisor,audience,null);}
    public Object healthCards(String code,int days,Long preview,Long supervisor,String audience,TimeRange range){return healthCardsInternal(code,days,preview,supervisor,false,audience,range);}
    public Object shareHealthCards(String code,int days) { return shareHealthCards(code,days,null); }
    public Object shareHealthCards(String code,int days,TimeRange range) {
        Context c=sharedContext(code,days,range);
        requireModule(c,"health");
        requireSharedPanel(c,"health");
        return healthCards(c);
    }
    public Object shareMetricDetail(String code,Long metricId,int days){return shareMetricDetail(code,metricId,days,null);}
    public Object shareMetricDetail(String code,Long metricId,int days,TimeRange range){
        Context c=sharedContext(code,days,range);requireModule(c,"health");
        var tabs = sharedTabs(c);
        if (!tabs.containsValue("health") && !tabs.containsValue("data")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该分享未开放指标详情");
        }
        List<Long> allowed=(List<Long>)c.params.get("metric");
        if(!allowed.contains(metricId))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该指标未获分享授权");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("definition",metricDefinition(metricId));
        var values=observationReads.trend(metricId,(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end"));
        result.put("values",values.get("data"));result.put("valueNotice",values.get("notice"));
        // 移动端详情要能画健康度趋势：trend() 的采集点 healthScore 固定为 null，
        // 这里补一份 metric_health_history 的区间记录（与专屏 PC 端图表同源）。
        result.put("healthTrend",observationReads.healthTrend(metricId,(LocalDateTime)c.params.get("start"),(LocalDateTime)c.params.get("end")));
        return result;
    }
    private List<Map<String,Object>> metricDefinition(Long metricId) {
        var rows=jdbc.queryForList("SELECT mi.id,si.system_name AS systemName,mc.category_name AS categoryName,mi.sub_category_name AS subCategoryName,mi.metric_code AS code,mi.metric_name AS name,mi.unit,mi.metric_definition AS definition,"+alarmThresholdExpr("mi.id")+" AS threshold,(SELECT a.alarm_condition FROM alarm_strategy a WHERE a.target_type='metric' AND a.strategy_type='metric' AND a.target_id=mi.id AND a.status=1 AND a.alarm_threshold IS NOT NULL ORDER BY a.id LIMIT 1) AS alarmCondition FROM metric_item mi JOIN metric_category mc ON mc.id=mi.category_id JOIN system_info si ON si.id=mc.system_id WHERE mi.id=?",metricId);
        rows.forEach(row -> { if(row.get("threshold") instanceof java.math.BigDecimal value) row.put("threshold",value.toPlainString()); });
        return rows;
    }
    /**
     * 标记白名单（不计分）指标。
     *
     * <p>{@code metric_health_rule.exempt=1} 的指标不出分、不参与任何聚合，
     * 专屏「指标与健康」据此隐藏它们；「数据来源」仍保留，因为采集和时效照样要看。
     */
    private void attachExempt(List<Map<String,Object>> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> ids=rows.stream().filter(row -> row.get("metricId")!=null)
            .map(row -> ((Number)row.get("metricId")).longValue()).distinct().toList();
        if (ids.isEmpty()) return;
        Set<Long> exempt=new HashSet<>(sql.queryForList(
            "SELECT metric_id FROM metric_health_rule WHERE exempt=1 AND metric_id IN (:ids)",
            Map.of("ids",ids),Long.class));
        rows.forEach(row -> {
            if (row.get("metricId")==null) return;
            row.put("exempt",exempt.contains(((Number)row.get("metricId")).longValue()));
        });
    }
    /** Keep the existing screen/share response boundary; never expose raw records or rule JSON. */
    private static List<Map<String,Object>> readableMetrics(List<Map<String,Object>> rows) {
        List<String> fields=List.of("id","metricId","systemId","systemName","categoryId","categoryName","subCategoryName",
            "metricName","metricCode","unit","dataTime","latestDataTime","latestCalculationTime","latestValue","metricValue",
            "healthScore","healthStatus","status","recordTime","sourceKind","sourceNotice","isStale","sourceTable","sourceField",
            "ruleType","collectFrequency","records","validRecords","trend","audienceCode","audienceName","exempt",
            "windowHealthScore","windowHealthSamples");
        return rows.stream().map(row -> {
            Map<String,Object> result=new LinkedHashMap<>();
            fields.forEach(key -> { if(row.containsKey(key)) result.put(key,row.get(key)); });
            return result;
        }).toList();
    }
    public Object eventWorkspace(String code,String kind,int days,int page,int size,Long systemId,Long categoryId,Long metricId,String status,String type,String keyword,Long preview,Long supervisor){return eventWorkspace(code,kind,days,page,size,systemId,categoryId,metricId,status,type,keyword,preview,supervisor,null,null);}
    public Object eventWorkspace(String code,String kind,int days,int page,int size,Long systemId,Long categoryId,Long metricId,String status,String type,String keyword,Long preview,Long supervisor,String audience){return eventWorkspace(code,kind,days,page,size,systemId,categoryId,metricId,status,type,keyword,preview,supervisor,audience,null);}
    public Object eventWorkspace(String code,String kind,int days,int page,int size,Long systemId,Long categoryId,Long metricId,String status,String type,String keyword,Long preview,Long supervisor,String audience,TimeRange range){
        return eventWorkspaceResult(context(code,days,preview,supervisor,audience,range),kind,page,size,systemId,categoryId,metricId,status,type,keyword);
    }
    /** 匿名分享链接的闭环明细分页（只读）。 */
    public Object shareEventWorkspace(String code,String kind,int days,int page,int size,Long systemId,Long categoryId,Long metricId,String status,String type,String keyword){return shareEventWorkspace(code,kind,days,page,size,systemId,categoryId,metricId,status,type,keyword,null);}
    public Object shareEventWorkspace(String code,String kind,int days,int page,int size,Long systemId,Long categoryId,Long metricId,String status,String type,String keyword,TimeRange range){
        Context c=sharedContext(code,days,range);
        // 与移动端分享的明细口径一致：闭环栏目没分享出去就不开放这批明细
        requireSharedPanel(c,"closure");
        return eventWorkspaceResult(c,kind,page,size,systemId,categoryId,metricId,status,type,keyword);
    }
    private Map<String,Object> eventWorkspaceResult(Context c,String kind,int page,int size,Long systemId,Long categoryId,Long metricId,String status,String type,String keyword){
        requireModule(c,"events");var s=spec(kind);
        if(page<0||size<1||size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数不合法");
        Map<String,Object> params=new HashMap<>(c.params);params.put("offset",page*size);params.put("limit",size);
        StringBuilder where=new StringBuilder(filter(kind)).append(" AND ").append(s.time).append(">=:start AND ").append(s.time).append("<=:end");
        if(systemId!=null){where.append(" AND e.system_id=:systemFilter");params.put("systemFilter",systemId);}
        if(categoryId!=null){where.append(" AND e.category_id=:categoryFilter");params.put("categoryFilter",categoryId);}
        if(metricId!=null){where.append(" AND e.metric_id=:metricFilter");params.put("metricFilter",metricId);}
        if("done".equals(status))where.append(" AND (").append(s.done).append(")");
        else if("pending".equals(status))where.append(" AND NOT (").append(s.done).append(")");
        if(type!=null&&!type.isBlank()){
            String col=kind.equals("alarm")?"alarm_type":kind.equals("issue")?"issue_type":"gongdan_type";
            where.append(" AND e.").append(col).append("=:typeFilter");params.put("typeFilter",type);
        }
        if(keyword!=null&&!keyword.isBlank()){where.append(" AND (e.").append(s.title).append(" LIKE :keyword OR e.").append(s.owner).append(" LIKE :keyword)");params.put("keyword","%"+keyword.trim()+"%");}
        String extra=kind.equals("issue")?",plan_resolve_time AS dueTime,issue_type AS type,issue_attribute AS attribute,resolve_status AS state":kind.equals("alarm")?",alarm_type AS type,process_status AS state,shield_status AS shieldStatus":",gongdan_type AS type,alarm_level AS level,closed_status AS state";
        Map<String,Object> stats=new LinkedHashMap<>();
        stats.put("total",sql.queryForObject("SELECT COUNT(*) FROM "+kind+" e WHERE "+where,params,Long.class));
        stats.put("pending",sql.queryForObject("SELECT COALESCE(SUM(NOT ("+s.done+")),0) FROM "+kind+" e WHERE "+where,params,Long.class));
        stats.put("completed",sql.queryForObject("SELECT COALESCE(SUM("+s.done+"),0) FROM "+kind+" e WHERE "+where,params,Long.class));
        stats.put("averageHours",sql.queryForObject("SELECT AVG(TIMESTAMPDIFF(SECOND,"+s.time+","+s.doneTime+"))/3600 FROM "+kind+" e WHERE "+where+" AND ("+s.done+") AND "+s.doneTime+">="+s.time,params,Double.class));
        stats.put("trend",sql.queryForList("SELECT DATE("+s.time+") AS day,COUNT(*) AS created FROM "+kind+" e WHERE "+where+" GROUP BY DATE("+s.time+") ORDER BY day",params));
        stats.put("completionTrend",sql.queryForList("SELECT DATE("+s.doneTime+") AS day,COUNT(*) AS completed FROM "+kind+" e WHERE "+where+" AND ("+s.done+") GROUP BY DATE("+s.doneTime+") ORDER BY day",params));
        Map<String,Object> result=new LinkedHashMap<>();result.put("total",stats.get("total"));result.put("stats",stats);result.put("rows",sql.queryForList("SELECT id,"+s.title+" AS title,"+s.owner+" AS owner,"+s.time+" AS time,"+s.doneTime+" AS completedTime,("+s.done+") AS done,system_id AS systemId,category_id AS categoryId,metric_id AS metricId"+extra+" FROM "+kind+" e WHERE "+where+" ORDER BY "+s.time+" DESC,id DESC LIMIT :limit OFFSET :offset",params));return result;
    }
    public Object events(String code,String kind,int days,int page,Long preview,Long supervisor){return events(code,kind,days,page,preview,supervisor,null,null);}
    public Object events(String code,String kind,int days,int page,Long preview,Long supervisor,String audience){return events(code,kind,days,page,preview,supervisor,audience,null);}
    public Object events(String code,String kind,int days,int page,Long preview,Long supervisor,String audience,TimeRange range){
        Context c=context(code,days,preview,supervisor,audience,range);requireModule(c,"events");var s=spec(kind);
        if(page<0||page>100000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String,Object> params=new HashMap<>(c.params);params.put("offset",page*20);
        String where=filter(kind)+" AND "+s.time+">=:start AND "+s.time+"<=:end";
        String extra=kind.equals("issue")?",plan_resolve_time AS dueTime":kind.equals("alarm")?",related_issue_id AS relatedIssueId":"";
        return Map.of("total",sql.queryForObject("SELECT COUNT(*) FROM "+kind+" e WHERE "+where,params,Long.class),"rows",sql.queryForList("SELECT id,"+s.title+" AS title,"+s.owner+" AS owner,"+s.time+" AS time,"+s.doneTime+" AS completedTime,("+s.done+") AS done,system_id AS systemId,category_id AS categoryId,metric_id AS metricId"+extra+" FROM "+kind+" e WHERE "+where+" ORDER BY "+s.time+" DESC,id DESC LIMIT 20 OFFSET :offset",params));
    }
    public Object shareEvents(String code,String kind,int days,int page){return shareEvents(code,kind,days,page,null);}
    public Object shareEvents(String code,String kind,int days,int page,TimeRange range){
        Context c=sharedContext(code,days,range);requireModule(c,"events");
        requireSharedPanel(c,"closure");
        var s=spec(kind);
        if(page<0||page>100000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Map<String,Object> params=new HashMap<>(c.params);params.put("offset",page*20);
        String where=filter(kind)+" AND "+s.time+">=:start AND "+s.time+"<=:end";
        String extra=kind.equals("issue")?",plan_resolve_time AS dueTime":kind.equals("alarm")?",related_issue_id AS relatedIssueId":"";
        return Map.of("total",sql.queryForObject("SELECT COUNT(*) FROM "+kind+" e WHERE "+where,params,Long.class),"rows",sql.queryForList("SELECT id,"+s.title+" AS title,"+s.owner+" AS owner,"+s.time+" AS time,"+s.doneTime+" AS completedTime,("+s.done+") AS done"+extra+" FROM "+kind+" e WHERE "+where+" ORDER BY "+s.time+" DESC,id DESC LIMIT 20 OFFSET :offset",params));
    }
    private static Object later(Object left,Object right){
        if(left==null) return right;
        if(right==null) return left;
        if(left instanceof LocalDateTime a && right instanceof LocalDateTime b) return a.isAfter(b)?left:right;
        if(left instanceof java.sql.Timestamp a && right instanceof java.sql.Timestamp b) return a.after(b)?left:right;
        return String.valueOf(left).compareTo(String.valueOf(right))>=0?left:right;
    }
    private Object operations(Context c){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("users",jdbc.queryForMap("SELECT COUNT(*) AS total,SUM(status=1) AS enabled FROM sys_user"));
        result.put("usage",sql.queryForList("SELECT DATE(login_time) AS day,COUNT(*) AS sessions,COUNT(DISTINCT username) AS activeUsers FROM user_session WHERE login_time>=:start AND login_time<=:end GROUP BY DATE(login_time) ORDER BY day",c.params));
        result.put("imports",sql.queryForList("SELECT table_name AS name,COUNT(*) AS batches,SUM(record_count) AS records,MAX(import_time) AS latest FROM original_data_import_log WHERE import_time>=:start AND import_time<=:end GROUP BY table_name ORDER BY latest DESC",c.params));
        result.put("scripts",sql.queryForList("SELECT status,COUNT(*) AS count,AVG(duration_ms) AS averageMs,MAX(start_time) AS latest FROM script_execution_log WHERE start_time>=:start AND start_time<=:end GROUP BY status",c.params));
        result.put("interfaces",jdbc.queryForMap("SELECT COUNT(*) AS configured,SUM(enabled=1) AS enabled FROM plugin_config"));
        result.put("calls",sql.queryForList("SELECT business_type AS businessType,exec_status AS status,COUNT(*) AS count,MAX(exec_time) AS latest FROM plugin_execution_log WHERE exec_time>=:start AND exec_time<=:end GROUP BY business_type,exec_status",c.params));
        result.put("calculations",sql.queryForMap("SELECT COUNT(DISTINCT metric_id) AS metrics,MAX(record_time) AS latest FROM metric_health_history WHERE record_time>=:start AND record_time<=:end",c.params));
        result.put("features",sql.queryForList("SELECT module,operation_type AS operation,COUNT(*) AS count FROM operation_log WHERE create_time>=:start AND create_time<=:end GROUP BY module,operation_type ORDER BY count DESC LIMIT 30",c.params));
        result.put("notice","采集统计来自导入日志；计算统计为已产出健康记录的指标数；接口统计仅覆盖已登记插件；操作日志不等同于页面访问。页面体验、全链路应执行量及历史积压尚无完整统计来源。");
        return result;
    }
}
