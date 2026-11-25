package com.metric.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.observe.dto.DashboardPolicy;
import com.metric.observe.dto.DashboardPolicy.Scope;
import com.metric.observe.entity.DashboardViewConfig;
import com.metric.observe.repository.DashboardViewConfigRepository;
import com.metric.security.service.CurrentUserProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class DashboardAccessService {
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider users;
    private final DashboardViewConfigRepository repository;
    private final ObjectMapper json;
    public DashboardAccessService(JdbcTemplate jdbc, CurrentUserProvider users,
                                  DashboardViewConfigRepository repository, ObjectMapper json) {
        this.jdbc = jdbc; this.users = users; this.repository = repository; this.json = json;
    }
    public boolean isAdmin() { return users.currentUser().map(u -> Integer.valueOf(1).equals(u.getIsAdmin())).orElse(false); }
    public Long userId() {
        return users.currentUser()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "当前登录用户未关联本地账号"))
                .getId();
    }
    public void requireAdmin() { if (!isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可配置专屏"); }
    public DashboardPolicy policy(DashboardViewConfig view) {
        try {
            DashboardPolicy p = json.readValue(view.getLayoutConfig(), DashboardPolicy.class);
            return p.version() == 1 ? p : null;
        } catch (Exception e) { return null; }
    }
    public Set<Long> roles(Long user) {
        return new HashSet<>(jdbc.queryForList("SELECT ur.role_id FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id AND r.status=1 WHERE ur.user_id=?", Long.class, user));
    }
    public boolean matches(DashboardPolicy.Grant grant, Long user, Set<Long> roles) {
        return "user".equals(grant.subjectType()) ? Objects.equals(user, grant.subjectId())
                : "role".equals(grant.subjectType()) && roles.contains(grant.subjectId());
    }
    public boolean canView(DashboardViewConfig view, Long user) {
        DashboardPolicy p = policy(view);
        if (!Integer.valueOf(1).equals(view.getStatus()) || p == null) return false;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sys_user WHERE id=? AND status=1", Boolean.class, user))) return false;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sys_user u WHERE u.id=? AND (u.is_admin=1 OR EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id AND r.status=1 JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id AND m.status=1 AND m.route_path='/dashboard-view' WHERE ur.user_id=u.id))", Boolean.class, user))) return false;
        Set<Long> roleIds = roles(user);
        return p.grants().stream().anyMatch(g -> matches(g, user, roleIds));
    }

    public String visibilitySummary(DashboardViewConfig view) {
        DashboardPolicy p = policy(view);
        if (p == null || p.grants().isEmpty()) return "未配置可见对象";
        List<String> names = new ArrayList<>();
        for (var grant : p.grants()) {
            String name = "user".equals(grant.subjectType())
                    ? jdbc.query("SELECT COALESCE(NULLIF(display_name,''),username) FROM sys_user WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, grant.subjectId())
                    : jdbc.query("SELECT role_name FROM sys_role WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, grant.subjectId());
            if (name != null && !name.isBlank()) names.add(("user".equals(grant.subjectType()) ? "用户：" : "角色：") + name);
        }
        return names.isEmpty() ? "未配置可见对象" : String.join("、", names);
    }
    public DashboardViewConfig requireView(String code, Long previewUser) {
        if (previewUser != null) requireAdmin();
        DashboardViewConfig view = repository.findByViewCode(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!(previewUser == null && isAdmin()) && !canView(view, previewUser == null ? userId() : previewUser))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看此专屏");
        return view;
    }
    public Map<String, DashboardScope.Node> catalog() {
        Map<String, DashboardScope.Node> nodes = new LinkedHashMap<>();
        jdbc.queryForList("SELECT id,system_name FROM system_info ORDER BY id").forEach(r -> {
            Long id = id(r.get("id")); nodes.put("system:"+id, new DashboardScope.Node("system:"+id,null,str(r.get("system_name")),"system",id,id,null,null,null));
        });
        jdbc.queryForList("SELECT id,system_id,category_name FROM metric_category ORDER BY id").forEach(r -> {
            Long id=id(r.get("id")), sid=id(r.get("system_id"));
            nodes.put("category:"+id,new DashboardScope.Node("category:"+id,"system:"+sid,str(r.get("category_name")),"category",id,sid,id,str(r.get("category_name")),null));
        });
        jdbc.queryForList("SELECT id,category_id,sub_category_name FROM metric_sub_category ORDER BY id").forEach(r -> {
            Long id=id(r.get("id")), cid=id(r.get("category_id")); DashboardScope.Node c=nodes.get("category:"+cid);
            if(c!=null) nodes.put("subcategory:"+id,new DashboardScope.Node("subcategory:"+id,c.key(),str(r.get("sub_category_name")),"subcategory",id,c.systemId(),cid,c.label(),str(r.get("sub_category_name"))));
        });
        // Items historically reference subcategories by name rather than FK. Resolve within their category only.
        Map<String, List<DashboardScope.Node>> subs=new HashMap<>();
        for(var n:nodes.values()) if(n.type().equals("subcategory")) subs.computeIfAbsent(n.categoryId()+"/"+n.label(), k->new ArrayList<>()).add(n);
        jdbc.queryForList("SELECT id,category_id,sub_category_name,metric_name FROM metric_item ORDER BY id").forEach(r -> {
            Long id=id(r.get("id")), cid=id(r.get("category_id")); DashboardScope.Node c=nodes.get("category:"+cid);
            if(c==null) return;
            List<DashboardScope.Node> s=subs.getOrDefault(cid+"/"+str(r.get("sub_category_name")),List.of());
            String parent=s.size()==1?s.get(0).key():c.key();
            nodes.put("metric:"+id,new DashboardScope.Node("metric:"+id,parent,str(r.get("metric_name")),"metric",id,c.systemId(),cid,c.label(),str(r.get("sub_category_name"))));
        });
        return nodes;
    }
    public List<Long> activeSupervisors(DashboardPolicy p) {
        return p.supervisorIds().stream().distinct().filter(id -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM sys_user WHERE id=? AND status=1",Boolean.class,id))).toList();
    }
    public boolean hasSupervisorGrant(DashboardViewConfig source, Long supervisor) {
        DashboardPolicy p=policy(source);
        if(!Integer.valueOf(1).equals(source.getStatus()) || p==null || !"supervisor".equals(p.template())) return false;
        Set<Long> roleIds=roles(supervisor);
        return p.grants().stream().anyMatch(g->matches(g,supervisor,roleIds));
    }
    public Set<String> scope(DashboardViewConfig view, Long user, Map<String, DashboardScope.Node> catalog) {
        DashboardPolicy p=policy(view);
        if(p==null) return Set.of();
        if("manager".equals(p.template())) {
            List<Scope> inherited=new ArrayList<>();
            for(Long supervisor:activeSupervisors(p)) {
                Set<Long> rs=roles(supervisor);
                for(DashboardViewConfig source:repository.findByStatusOrderBySortOrderAsc(1)) {
                    DashboardPolicy sp=policy(source);
                    if(sp!=null && hasSupervisorGrant(source, supervisor))
                        sp.grants().stream().filter(g->matches(g,supervisor,rs)).forEach(g->inherited.add(g.scope()));
                }
            }
            return DashboardScope.resolve(inherited,catalog);
        }
        Set<Long> rs=roles(user);
        return DashboardScope.resolve(p.grants().stream().filter(g->matches(g,user,rs)).map(DashboardPolicy.Grant::scope).toList(),catalog);
    }
    public void validate(DashboardViewConfig view) {
        requireAdmin();
        if(view.getViewName()==null || view.getViewName().isBlank() || view.getViewName().length()>100 || view.getViewCode()==null || !view.getViewCode().matches("[A-Za-z0-9_-]{1,50}")) bad("请填写名称及有效视图代码");
        if(!Set.of(0,1).contains(view.getStatus()==null?-1:view.getStatus())) bad("无效发布状态");
        DashboardPolicy p=policy(view);
        if(p==null || !Set.of("supervisor","leader","operator","manager").contains(p.template())) bad("请选择有效专屏模板");
        if(p.grants().isEmpty()) bad("至少配置一个授权角色或用户");
        if(p.grants().size()>200 || p.supervisorIds().size()>100) bad("授权对象过多");
        if("manager".equals(p.template()) && p.supervisorIds().isEmpty()) bad("请选择关联业务主管");
        Map<String,DashboardScope.Node> nodes=catalog();
        for(var g:p.grants()) {
            if(!Set.of("user","role").contains(g.subjectType()==null?"":g.subjectType()) || g.subjectId()==null) bad("无效授权对象");
            String table="user".equals(g.subjectType())?"sys_user":"sys_role";
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM "+table+" WHERE id=? AND status=1",Boolean.class,g.subjectId()))) bad("授权对象不存在或已停用");
            if(!nodes.keySet().containsAll(g.scope().include()) || !nodes.keySet().containsAll(g.scope().exclude())) bad("范围节点已删除，请重新选择");
            if(!"manager".equals(p.template()) && g.scope().include().isEmpty()) bad("每条授权必须明确选择业务范围");
        }
        if(!Set.of("health","data","events","report","team","operations").containsAll(p.modules())) bad("无效展示模块");
        if(!Set.of("split","all").contains(p.layoutMode())) bad("无效页面布局，仅支持分栏或全部");
        if(!DashboardPolicy.allowedTabs(p.template()).containsAll(p.tabs())) bad("无效展示页签");
        if(p.modules().contains("operations") && !"operator".equals(p.template())) bad("平台运行模块仅适用于平台运营模板");
        if(p.modules().contains("team") && !Set.of("manager","leader").contains(p.template())) bad("人员模块仅适用于经理或领导模板");
        if(!"manager".equals(p.template()) && !p.supervisorIds().isEmpty()) bad("仅科室经理可关联主管");
        if(activeSupervisors(p).size()!=new HashSet<>(p.supervisorIds()).size()) bad("关联主管已停用或不存在");
    }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public static Long id(Object o) { return o==null?null:((Number)o).longValue(); }
    public static String str(Object o) { return o==null?"":o.toString(); }
}
