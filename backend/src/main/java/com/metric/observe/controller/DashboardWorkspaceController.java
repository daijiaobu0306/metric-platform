package com.metric.observe.controller;

import com.metric.observe.service.*;
import com.metric.observe.repository.DashboardViewConfigRepository;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.metric.observe.service.DashboardWorkspaceService.resolveRange;

@RestController
@RequestMapping("/api/dashboard-view/workspace")
public class DashboardWorkspaceController {
    private final DashboardAccessService access;
    private final DashboardWorkspaceService workspace;
    private final DashboardViewConfigRepository repository;
    public DashboardWorkspaceController(DashboardAccessService access,DashboardWorkspaceService workspace,DashboardViewConfigRepository repository){this.access=access;this.workspace=workspace;this.repository=repository;}
    private Map<String,Object> ok(Object data){return Map.of("code",200,"data",data);}
    @GetMapping("/capabilities") public Object capabilities(){return ok(Map.of("canConfigure",access.isAdmin()));}
    @GetMapping("/configuration") public Object configuration(){access.requireAdmin();return ok(workspace.configuration(repository.findAll()));}
    @GetMapping("/{code}") public Object overview(@PathVariable String code,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,@RequestParam(defaultValue="true") boolean includeMetrics,@RequestParam(required=false) String audience,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime){return ok(workspace.overview(code,days,previewUser,supervisorId,includeMetrics,audience,resolveRange(startTime,endTime)));}
    @GetMapping("/{code}/metric-panel")
    public Object metricPanel(@PathVariable String code,@RequestParam(defaultValue="7") int days,
            @RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,
            @RequestParam(defaultValue="all") String panel,
            @RequestParam(required=false) String audience,
            @RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime) {
        return ok(workspace.metricPanel(code,days,previewUser,supervisorId,panel,audience,resolveRange(startTime,endTime)));
    }
    @GetMapping("/{code}/health") public Object health(@PathVariable String code,@RequestParam String node,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime){return ok(workspace.health(code,node,days,previewUser,supervisorId,resolveRange(startTime,endTime)));}
    @GetMapping("/{code}/health-cards") public Object healthCards(@PathVariable String code,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,@RequestParam(required=false) String audience,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime){return ok(workspace.healthCards(code,days,previewUser,supervisorId,audience,resolveRange(startTime,endTime)));}
    @GetMapping("/{code}/data-meta") public Object dataMeta(@PathVariable String code,@RequestParam(defaultValue="7") int days,@RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,@RequestParam(required=false) String audience,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime){return ok(workspace.dataMeta(code,days,previewUser,supervisorId,audience,resolveRange(startTime,endTime)));}
    @GetMapping("/{code}/data-rows") public Object dataRows(@PathVariable String code,@RequestParam String category,@RequestParam String subCategory,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,@RequestParam(required=false) String audience,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime){return ok(workspace.dataRows(code,category,subCategory,page,size,previewUser,supervisorId,audience,resolveRange(startTime,endTime)));}
    @GetMapping("/{code}/event-workspace") public Object eventWorkspace(@PathVariable String code,@RequestParam String kind,@RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) Long systemId,@RequestParam(required=false) Long categoryId,@RequestParam(required=false) Long metricId,@RequestParam(required=false) String status,@RequestParam(required=false) String type,@RequestParam(required=false) String keyword,@RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,@RequestParam(required=false) String audience,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime){return ok(workspace.eventWorkspace(code,kind,days,page,size,systemId,categoryId,metricId,status,type,keyword,previewUser,supervisorId,audience,resolveRange(startTime,endTime)));}
    @GetMapping("/{code}/events") public Object events(@PathVariable String code,@RequestParam String kind,@RequestParam(defaultValue="7") int days,@RequestParam(defaultValue="0") int page,@RequestParam(required=false) Long previewUser,@RequestParam(required=false) Long supervisorId,@RequestParam(required=false) String audience,@RequestParam(required=false) String startTime,@RequestParam(required=false) String endTime){return ok(workspace.events(code,kind,days,page,previewUser,supervisorId,audience,resolveRange(startTime,endTime)));}
}
