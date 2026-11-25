package com.metric.config.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.config.entity.SystemConfig;
import com.metric.config.repository.SystemConfigRepository;
import com.metric.security.service.CurrentUserProvider;
import com.metric.security.service.SessionService;
import com.metric.shared.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/external-menus")
public class ExternalMenuController {
    public record Menu(String id,String name,String description,String parent,String icon,int sort,boolean enabled,boolean folder,String url,String mode,String auth,String credentialEnv,String parameter,boolean everyone,List<Long> roles) {}
    public record Configuration(String version,List<Menu> items) {}
    private final SystemConfigRepository repository;
    private final CurrentUserProvider current;
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private static final String KEY="navigation.external_menus.v1";
    private static final Set<String> BUILTINS=Set.of("/observation","/report","/data","/alarm","/gongdan","/issue","/metric","/audit","/config","/dashboard-view","/script");
    public ExternalMenuController(SystemConfigRepository repository,CurrentUserProvider current,SessionService sessions,ObjectMapper mapper,JdbcTemplate jdbc) {
        this.repository=repository;this.current=current;this.sessions=sessions;this.mapper=mapper;this.jdbc=jdbc;
    }
    private void admin() { if(!current.currentUser().map(u -> Integer.valueOf(1).equals(u.getIsAdmin())).orElse(false)) throw new IllegalArgumentException("仅管理员可以管理菜单"); }
    private SystemConfig config() { return repository.findByConfigKey(KEY).orElse(null); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("菜单配置格式错误",e);} }
    private List<Menu> read() { try {SystemConfig c=config();return c==null ? List.of() : mapper.readValue(c.getConfigValue(),new com.fasterxml.jackson.core.type.TypeReference<List<Menu>>(){});}catch(Exception e){throw new IllegalArgumentException("菜单配置读取失败",e);} }
    private String version() { SystemConfig c=config();return c==null ? "new" : org.springframework.util.DigestUtils.md5DigestAsHex(c.getConfigValue().getBytes(StandardCharsets.UTF_8)); }
    @GetMapping("/admin") public ApiResponse<?> management() { admin();return ApiResponse.success(Map.of("version",version(),"items",read(),"roles",jdbc.queryForList("SELECT id,role_name AS name FROM sys_role WHERE status=1 ORDER BY id"))); }
    @PutMapping("/admin") @Transactional public ApiResponse<?> save(@RequestBody Configuration body) {
        admin();jdbc.queryForList("SELECT id FROM system_config WHERE config_key=? FOR UPDATE",KEY);
        if(!Objects.equals(version(),body.version()))throw new IllegalArgumentException("配置已变更，请刷新后重试");
        if(body.items()==null || body.items().size()>100)throw new IllegalArgumentException("最多100个外部菜单");
        Set<String> ids=new HashSet<>();Map<String,Menu> index=new HashMap<>();
        for(Menu m:body.items()) {
            if(m.id()==null || !m.id().matches("[a-z][a-z0-9_-]{0,39}") || !ids.add(m.id()))throw new IllegalArgumentException("菜单编码须唯一，由小写字母开头，包含字母数字、下划线或短横线");
            if(m.name()==null || m.name().isBlank() || m.name().length()>40)throw new IllegalArgumentException("菜单名称必填，最多40字");
            if(m.description()!=null && m.description().length()>500)throw new IllegalArgumentException("菜单描述最多500字");
            if(!Set.of("Link","Monitor","DataBoard","Setting","Document","Tools","Connection","Grid","Histogram","Folder","Tickets","Compass","Search","Management").contains(m.icon()))throw new IllegalArgumentException("请选择内置图标");
            if(m.folder() && m.parent()!=null && !m.parent().isBlank())throw new IllegalArgumentException("目录只能位于一级导航");
            if(!m.folder()) {
                URI u;try{u=URI.create(m.url());}catch(Exception e){throw new IllegalArgumentException("URL无效");}
                if(u.getScheme()==null || !Set.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null)throw new IllegalArgumentException("URL须为完整http/https地址，不能含账号密码");
                if(!Set.of("embed","tab").contains(m.mode()) || !Set.of("none","signed","env-query").contains(m.auth()))throw new IllegalArgumentException("打开或鉴权方式无效");
                if(!"none".equals(m.auth()) && (m.credentialEnv()==null || !m.credentialEnv().matches("EXTERNAL_MENU_[A-Z0-9_]{1,60}") || m.parameter()==null || !m.parameter().matches("[a-zA-Z][a-zA-Z0-9_]{0,39}")))throw new IllegalArgumentException("鉴权需指定EXTERNAL_MENU_开头的环境变量和有效参数名");
            }
            if(m.roles()==null)throw new IllegalArgumentException("角色配置缺失");index.put(m.id(),m);
        }
        for(Menu m:body.items()) if(m.parent()!=null && !m.parent().isBlank() && !BUILTINS.contains(m.parent())) {
            Menu parent=index.get(m.parent());
            if(parent==null || !parent.folder() || (parent.parent()!=null && !parent.parent().isBlank()) || parent.id().equals(m.id()))throw new IllegalArgumentException("仅支持一级目录下的二级菜单");
        }
        SystemConfig c=config();if(c==null){c=new SystemConfig();c.setConfigKey(KEY);c.setConfigType("navigation");c.setStatus(1);c.setDescription("外部菜单配置，不存储凭据明文");}
        c.setConfigValue(json(body.items()));repository.saveAndFlush(c);return ApiResponse.success(Map.of("version",version()));
    }
    private boolean allowed(Menu m) {
        var user=current.currentUser().orElseThrow(() -> new IllegalArgumentException("请先登录"));
        if(Integer.valueOf(1).equals(user.getIsAdmin()))return true;
        if(m.parent()!=null && BUILTINS.contains(m.parent()) && !sessions.computePermissions(user).contains(m.parent()))return false;
        return m.everyone() || (!m.roles().isEmpty() && jdbc.queryForList("SELECT r.id FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.status=1",Long.class,user.getId()).stream().anyMatch(m.roles()::contains));
    }
    private List<Menu> visible() {
        List<Menu> all=read();Map<String,Menu> index=new HashMap<>();all.forEach(m -> index.put(m.id(),m));
        return all.stream().filter(Menu::enabled).filter(this::allowed).filter(m -> {
            Menu p=index.get(m.parent());return p==null || (p.enabled() && allowed(p));
        }).sorted(Comparator.comparingInt(Menu::sort).thenComparing(Menu::id)).toList();
    }
    @GetMapping public ApiResponse<?> navigation() { return ApiResponse.success(visible()); }
    @PostMapping("/{id}/launch") public ApiResponse<?> launch(@PathVariable String id) {
        Menu m=visible().stream().filter(x -> x.id().equals(id) && !x.folder()).findFirst().orElseThrow(() -> new IllegalArgumentException("菜单不存在、已停用或无访问权限"));
        String url=m.url();
        if(!"none".equals(m.auth())) {
            String secret=System.getenv(m.credentialEnv());if(secret==null || secret.isBlank())throw new IllegalArgumentException("服务器尚未配置此菜单凭据环境变量");
            String token=secret;
            if("signed".equals(m.auth())) {
                if(secret.length()<32)throw new IllegalArgumentException("签名密钥至少32字符");
                long now=System.currentTimeMillis()/1000;String sub=current.currentUser().orElseThrow().getUsername();
                var base=Base64.getUrlEncoder().withoutPadding();String header=base.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
                String payload=base.encodeToString(json(Map.of("iss","metric-platform","aud",m.id(),"sub",sub,"iat",now,"exp",now+60,"jti",UUID.randomUUID().toString())).getBytes(StandardCharsets.UTF_8));
                try {var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));token=header+"."+payload+"."+base.encodeToString(mac.doFinal((header+"."+payload).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalArgumentException("签名失败",e);}
            }
            int hash=url.indexOf('#');String fragment=hash<0 ? "" : url.substring(hash);String baseUrl=hash<0 ? url : url.substring(0,hash);
            url=baseUrl+(baseUrl.contains("?") ? "&" : "?")+m.parameter()+"="+URLEncoder.encode(token,StandardCharsets.UTF_8)+fragment;
        }
        return ApiResponse.success(Map.of("url",url,"mode",m.mode(),"name",m.name()));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ApiResponse<?> invalid(IllegalArgumentException e) { return ApiResponse.error(e.getMessage()); }
}
