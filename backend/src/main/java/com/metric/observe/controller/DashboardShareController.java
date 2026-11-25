package com.metric.observe.controller;

import com.metric.observe.service.DashboardShareFileService;
import com.metric.observe.service.DashboardShareService;
import com.metric.observe.service.DashboardWorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.metric.observe.service.DashboardWorkspaceService.resolveRange;

@RestController
@RequestMapping("/api/dashboard-share")
public class DashboardShareController {
    private final DashboardShareService shares; private final DashboardWorkspaceService workspace;
    private final DashboardShareFileService files;
    public DashboardShareController(DashboardShareService shares,DashboardWorkspaceService workspace,DashboardShareFileService files){this.shares=shares;this.workspace=workspace;this.files=files;}
    private Map<String,Object> ok(Object data){return Map.of("code",200,"data",data);}
    @PostMapping public Object create(@RequestBody Map<String,Object> body,HttpServletRequest request){
        String baseUrl=body.get("baseUrl") instanceof String text&&!text.isBlank()?text:origin(request);
        // target=mobile（默认，H5 精简页）/ pc（完整 PC 专屏页）；PC 链接不写入鉴权文件。
        String target=body.get("target") instanceof String text&&!text.isBlank()?text:"mobile";
        return ok(shares.create(((Number)body.get("viewId")).longValue(),body.get("expiresDays") instanceof Number n?n.intValue():7,Boolean.TRUE.equals(body.get("permanent")),baseUrl,target));
    }
    @GetMapping public Object list(@RequestParam Long viewId){return ok(shares.list(viewId));}
    @PostMapping("/{id}/revoke") public Object revoke(@PathVariable Long id){shares.revoke(id);return ok(true);}
    @GetMapping("/file-config") public Object fileConfig(){return ok(files.config());}
    @PutMapping("/file-config") public Object saveFileConfig(@RequestBody Map<String,Object> body){return ok(files.saveConfig(body));}
    @PostMapping("/file-sync") public Object fileSync(){return ok(files.sync());}
    @PostMapping("/s/{publicId}/session") public Object open(@PathVariable String publicId,@RequestHeader(value="X-Share-Secret",required=false) String secret,HttpServletRequest request){return ok(shares.open(publicId,secret,request));}
    // 可选自定义区间 startTime/endTime：先校验分享会话（匿名鉴权边界不变），再解析时间窗。
    @GetMapping("/s/{publicId}/overview") public Object overview(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareOverview(s.viewCode(),days,resolveRange(startTime,endTime)));}
    @GetMapping("/s/{publicId}/panel")
    public Object panel(@PathVariable String publicId, @RequestHeader("X-Share-Session") String token,
                        @RequestParam(defaultValue="7") int days, @RequestParam String panel,
                        @RequestParam(required=false) String startTime, @RequestParam(required=false) String endTime,
                        HttpServletRequest request) {
        var session = shares.session(publicId,token,request);
        return ok(workspace.sharePanel(session.viewCode(),days,panel,resolveRange(startTime,endTime)));
    }

    @GetMapping("/s/{publicId}/health-cards") public Object healthCards(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareHealthCards(s.viewCode(),days,resolveRange(startTime,endTime)));}
    /** PC 分享页的节点健康度（健康度观测/指标详情下钻用）。 */
    @GetMapping("/s/{publicId}/health") public Object health(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestParam String node,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareHealth(s.viewCode(),node,days,resolveRange(startTime,endTime)));}
    /** PC 分享页的明细维度目录（数据来源页签）。 */
    @GetMapping("/s/{publicId}/data-meta") public Object dataMeta(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareDataMeta(s.viewCode(),days,resolveRange(startTime,endTime)));}
    /** PC 分享页的原始明细行（数据来源 → 明细列表）。 */
    @GetMapping("/s/{publicId}/data-rows") public Object dataRows(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestParam String category,@RequestParam String subCategory,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareDataRows(s.viewCode(),category,subCategory,page,size,resolveRange(startTime,endTime)));}
    /** PC 分享页的闭环明细（闭环管理页签，只读）。 */
    @GetMapping("/s/{publicId}/event-workspace") public Object eventWorkspace(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestParam String kind,@RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) Long systemId,@RequestParam(required=false) Long categoryId,@RequestParam(required=false) Long metricId,@RequestParam(required=false) String status,@RequestParam(required=false) String type,@RequestParam(required=false) String keyword,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareEventWorkspace(s.viewCode(),kind,days,page,size,systemId,categoryId,metricId,status,type,keyword,resolveRange(startTime,endTime)));}
    @GetMapping("/s/{publicId}/metrics/{metricId}") public Object metricDetail(@PathVariable String publicId,@PathVariable Long metricId,@RequestHeader("X-Share-Session") String token,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareMetricDetail(s.viewCode(),metricId,days,resolveRange(startTime,endTime)));}
    @GetMapping("/s/{publicId}/event-rows") public Object eventRows(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestParam String kind,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime,HttpServletRequest req){var s=shares.session(publicId,token,req);return ok(workspace.shareEvents(s.viewCode(),kind,days,page,resolveRange(startTime,endTime)));}
    @PostMapping("/s/{publicId}/events") public Object event(@PathVariable String publicId,@RequestHeader("X-Share-Session") String token,@RequestBody Map<String,Object> body,HttpServletRequest req){var s=shares.session(publicId,token,req);shares.event(s,String.valueOf(body.get("name")),(String)body.get("targetType"),(String)body.get("targetId"),(String)body.getOrDefault("page","screen"),req);return ok(true);}
    @GetMapping("/statistics") public Object statistics(@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime end){return ok(shares.statistics(start,end));}

    /** 兜底：前端未传访问地址时按请求头还原，兼容反向代理。 */
    private String origin(HttpServletRequest request){
        String proto=request.getHeader("X-Forwarded-Proto");
        if(proto==null||proto.isBlank()) proto=request.getScheme();
        String host=request.getHeader("X-Forwarded-Host");
        if(host==null||host.isBlank()) host=request.getHeader("Host");
        if(host==null||host.isBlank()) host=request.getServerName()+":"+request.getServerPort();
        return proto+"://"+host;
    }
}
