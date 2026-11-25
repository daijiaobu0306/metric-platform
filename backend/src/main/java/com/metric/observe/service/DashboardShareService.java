package com.metric.observe.service;

import com.metric.observe.entity.DashboardViewConfig;
import com.metric.observe.repository.DashboardViewConfigRepository;
import com.metric.security.service.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated, read-only authorization for anonymous shared screens. */
@Service
public class DashboardShareService {
    // Reserved value for permanent links in the existing non-null DATETIME column.
    private static final LocalDateTime PERMANENT_EXPIRATION = LocalDateTime.of(9999, 12, 31, 0, 0);
    private final JdbcTemplate jdbc;
    private final DashboardViewConfigRepository views;
    private final DashboardAccessService access;
    private final DashboardShareFileService files;
    private final DashboardShareSecretCipher cipher;
    private final CurrentUserProvider users;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Window> limits = new ConcurrentHashMap<>();
    private record Window(long startedAt, int count) {}
    public record ShareSession(Long shareId, Long sessionId, String viewCode, int minRefreshSeconds) {}

    public DashboardShareService(JdbcTemplate jdbc, DashboardViewConfigRepository views, DashboardAccessService access,
                                 DashboardShareFileService files, DashboardShareSecretCipher cipher, CurrentUserProvider users) {
        this.jdbc=jdbc; this.views=views; this.access=access; this.files=files; this.cipher=cipher; this.users=users;
    }
    public Map<String,Object> create(Long viewId, Integer expiresDays, boolean permanent, String baseUrl, String target) {
        access.requireAdmin();
        DashboardViewConfig view=views.findById(viewId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!Integer.valueOf(1).equals(view.getStatus()) || access.policy(view)==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"只能分享已发布且已配置授权的专屏");
        int days=Math.max(1,Math.min(expiresDays==null?7:expiresDays,30));
        LocalDateTime expiresAt = permanent ? PERMANENT_EXPIRATION : LocalDateTime.now().plusDays(days);
        String publicId=randomToken(18), secret=randomToken(32);
        jdbc.update("INSERT INTO dashboard_share_link(public_id,secret_hash,secret_cipher,view_id,status,expires_at,max_refresh_seconds,created_by) VALUES(?,?,?,?,?,?,?,?)",publicId,hash(secret),cipher.encrypt(secret),viewId,1,expiresAt,30,users.currentUser().map(u->u.getId()).orElse(null));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("publicId",publicId);
        result.put("secret",secret);
        result.put("expiresAt",expiresAt);
        result.put("permanent",permanent);
        // pc=完整 PC 专屏页（复用平台内页面），mobile=现有 H5 精简页；
        // 两种页面用同一条分享记录、同一个密钥，只是入口路径不同。
        boolean pc="pc".equalsIgnoreCase(target);
        result.put("target",pc?"pc":"mobile");
        String path=(pc?"/metric-code/screen/pc/s/":"/metric-code/screen/s/")+publicId+"#k="+secret;
        result.put("url",path);
        String warning=null;
        if(!pc){
            // 移动端分享链接同步写入 appuser 鉴权文件；PC 链接按要求不写入。
            Long linkId=jdbc.queryForObject("SELECT id FROM dashboard_share_link WHERE public_id=?",Long.class,publicId);
            warning=files.record(linkId,secret,baseUrl);
        }
        if(warning!=null) result.put("fileWarning",warning);
        // 对外地址可能在 record 时刚被自动记录，这里取最新值拼完整链接
        String base=files.externalBaseUrl();
        result.put("shareUrl",base.isEmpty()?path:base+path);
        return result;
    }
    /** 管理员历史列表：能解密的历史链接直接回传完整地址，可随时再次复制。 */
    public List<Map<String,Object>> list(Long viewId) {
        access.requireAdmin();
        List<Map<String,Object>> links = jdbc.queryForList("SELECT l.id,l.public_id AS publicId,l.status,l.expires_at AS expiresAt,l.max_refresh_seconds AS maxRefreshSeconds,l.create_time AS createTime,l.last_used_at AS lastUsedAt,l.secret_cipher AS secretCipher,u.username AS createdByName,(SELECT COUNT(*) FROM dashboard_share_event e WHERE e.share_id=l.id) AS eventCount FROM dashboard_share_link l LEFT JOIN sys_user u ON u.id=l.created_by WHERE l.view_id=? ORDER BY l.id DESC",viewId);
        String base=files.externalBaseUrl();
        for (Map<String,Object> link : links) {
            link.put("permanent", PERMANENT_EXPIRATION.equals(dateTime(link.get("expiresAt"))));
            Object rawCipher=link.remove("secretCipher");
            String secret=rawCipher==null?null:cipher.decrypt(String.valueOf(rawCipher));
            link.put("recoverable", secret!=null);
            if (secret!=null) {
                String path="/metric-code/screen/s/"+link.get("publicId")+"#k="+secret;
                link.put("shareUrl", base.isEmpty()?path:base+path);
                // 同一条分享记录也能生成 PC 完整页链接，历史链接无需重新生成即可复制使用
                String pcPath="/metric-code/screen/pc/s/"+link.get("publicId")+"#k="+secret;
                link.put("pcShareUrl", base.isEmpty()?pcPath:base+pcPath);
            }
        }
        return links;
    }
    public void revoke(Long id) {
        access.requireAdmin();
        List<String> publicIds=jdbc.queryForList("SELECT public_id FROM dashboard_share_link WHERE id=?",String.class,id);
        jdbc.update("UPDATE dashboard_share_link SET status=0 WHERE id=?",id);
        publicIds.forEach(files::remove);
    }

    public Map<String,Object> open(String publicId,String secret,HttpServletRequest request) {
        limit("open:"+clientIp(request),10,60);
        Map<String,Object> row=find(publicId);
        if(secret==null || !MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.UTF_8),String.valueOf(row.get("secret_hash")).getBytes(StandardCharsets.UTF_8))) deny();
        Long shareId=((Number)row.get("id")).longValue();
        String token=randomToken(32); LocalDateTime expires=LocalDateTime.now().plusMinutes(30);
        jdbc.update("INSERT INTO dashboard_share_session(share_id,token_hash,ip_hash,user_agent,device_type,expires_at) VALUES(?,?,?,?,?,?)",shareId,hash(token),hash(clientIp(request)),agent(request),device(request),expires);
        Long sessionId=jdbc.queryForObject("SELECT id FROM dashboard_share_session WHERE token_hash=?",Long.class,hash(token));
        jdbc.update("UPDATE dashboard_share_link SET last_used_at=NOW() WHERE id=?",shareId);
        event(shareId,sessionId,"screen_open",null,null,"screen",request);
        return Map.of("token",token,"expiresAt",expires,"viewName",row.get("view_name"),"template",row.get("template"),"minRefreshSeconds",row.get("max_refresh_seconds"));
    }
    public ShareSession session(String publicId,String token,HttpServletRequest request) {
        limit("read:"+publicId+":"+clientIp(request),90,60);
        if(token==null || token.isBlank()) deny();
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT l.id AS shareId,l.public_id,l.status,l.expires_at,s.id AS sessionId,s.expires_at AS sessionExpires,v.view_code AS viewCode,l.max_refresh_seconds AS refreshSeconds FROM dashboard_share_link l JOIN dashboard_share_session s ON s.share_id=l.id JOIN dashboard_view_config v ON v.id=l.view_id WHERE l.public_id=? AND s.token_hash=?",publicId,hash(token));
        if(rows.isEmpty()) deny(); Map<String,Object> r=rows.get(0);
        if(!Integer.valueOf(1).equals(((Number)r.get("status")).intValue()) || linkExpired(r.get("expires_at")) || dateTime(r.get("sessionExpires")).isBefore(LocalDateTime.now())) deny();
        Long shareId=((Number)r.get("shareId")).longValue(),sessionId=((Number)r.get("sessionId")).longValue();
        jdbc.update("UPDATE dashboard_share_session SET last_active_at=NOW() WHERE id=?",sessionId);
        return new ShareSession(shareId,sessionId,String.valueOf(r.get("viewCode")),((Number)r.get("refreshSeconds")).intValue());
    }
    public void event(ShareSession session,String name,String targetType,String targetId,String page,HttpServletRequest request) {
        if(!Set.of("tab_changed","metric_card_clicked","fullscreen_entered","auto_scroll_changed","refresh_triggered","detail_opened","page_view").contains(name)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支持的行为事件");
        event(session.shareId,session.sessionId,name,targetType,targetId,page,request);
    }
    public Map<String,Object> statistics(LocalDateTime start,LocalDateTime end) {
        access.requireAdmin();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("summary",jdbc.queryForMap("SELECT COUNT(DISTINCT share_id) AS sharedScreens,COUNT(DISTINCT session_id) AS visitors,COUNT(*) AS events FROM dashboard_share_event WHERE create_time BETWEEN ? AND ?",start,end));
        result.put("trend",jdbc.queryForList("SELECT DATE(create_time) AS day,COUNT(DISTINCT session_id) AS visitors,COUNT(*) AS events FROM dashboard_share_event WHERE create_time BETWEEN ? AND ? GROUP BY DATE(create_time) ORDER BY day",start,end));
        result.put("devices",jdbc.queryForList("SELECT device_type AS deviceType,COUNT(DISTINCT session_id) AS visitors,COUNT(*) AS events FROM dashboard_share_event WHERE create_time BETWEEN ? AND ? GROUP BY device_type ORDER BY visitors DESC",start,end));
        result.put("actions",jdbc.queryForList("SELECT event_name AS eventName,COUNT(*) AS count FROM dashboard_share_event WHERE create_time BETWEEN ? AND ? GROUP BY event_name ORDER BY count DESC",start,end));
        result.put("recent",jdbc.queryForList("SELECT e.create_time AS createTime,e.event_name AS eventName,e.page_name AS pageName,e.target_type AS targetType,e.target_id AS targetId,e.device_type AS deviceType,l.public_id AS publicId,v.view_name AS viewName FROM dashboard_share_event e JOIN dashboard_share_link l ON l.id=e.share_id JOIN dashboard_view_config v ON v.id=l.view_id WHERE e.create_time BETWEEN ? AND ? ORDER BY e.id DESC LIMIT 100",start,end));
        return result;
    }
    private Map<String,Object> find(String publicId) { if(publicId==null || !publicId.matches("[A-Za-z0-9_-]{20,48}")) deny(); List<Map<String,Object>> rows=jdbc.queryForList("SELECT l.*,v.view_name,v.view_code,JSON_UNQUOTE(JSON_EXTRACT(v.layout_config,'$.template')) AS template FROM dashboard_share_link l JOIN dashboard_view_config v ON v.id=l.view_id WHERE l.public_id=?",publicId); if(rows.isEmpty()) deny(); Map<String,Object> r=rows.get(0); if(!Integer.valueOf(1).equals(((Number)r.get("status")).intValue()) || linkExpired(r.get("expires_at"))) deny(); return r; }
    private void event(Long shareId,Long sessionId,String name,String targetType,String targetId,String page,HttpServletRequest req){jdbc.update("INSERT INTO dashboard_share_event(share_id,session_id,event_name,target_type,target_id,page_name,ip_hash,device_type,user_agent) VALUES(?,?,?,?,?,?,?,?,?)",shareId,sessionId,name,targetType,targetId,page,hash(clientIp(req)),device(req),agent(req));}
    private void limit(String key,int max,int seconds){long now=System.currentTimeMillis();Window old=limits.get(key);Window next=old==null||now-old.startedAt>seconds*1000L?new Window(now,1):new Window(old.startedAt,old.count+1);limits.put(key,next);if(next.count>max)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请求过于频繁，请稍后再试");}
    private void deny(){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"链接无效、已失效或无访问权限");}
    private String randomToken(int bytes){byte[] b=new byte[bytes];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private boolean linkExpired(Object value) {
        LocalDateTime expiration = dateTime(value);
        return !PERMANENT_EXPIRATION.equals(expiration) && expiration.isBefore(LocalDateTime.now());
    }
    private LocalDateTime dateTime(Object value){if(value instanceof LocalDateTime time)return time;if(value instanceof java.sql.Timestamp time)return time.toLocalDateTime();throw new IllegalStateException("无效时间字段");}
    private String clientIp(HttpServletRequest r){String x=r.getHeader("X-Forwarded-For");return x==null||x.isBlank()?r.getRemoteAddr():x.split(",")[0].trim();}
    private String agent(HttpServletRequest r){String v=Optional.ofNullable(r.getHeader("User-Agent")).orElse("");return v.substring(0,Math.min(v.length(),500));}
    private String device(HttpServletRequest r){String a=agent(r).toLowerCase(Locale.ROOT);return a.contains("tablet")?"tablet":a.contains("mobi")?"mobile":"desktop";}
}
