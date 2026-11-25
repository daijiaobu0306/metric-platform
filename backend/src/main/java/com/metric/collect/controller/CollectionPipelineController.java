package com.metric.collect.controller;

import com.metric.collect.service.CollectionPipeline;
import com.metric.collect.service.CollectionSource;
import com.metric.collect.service.CollectionTaskManagementService;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/collection/tasks")
public class CollectionPipelineController {
    private final CollectionPipeline pipeline;
    private final CurrentUserProvider users;
    @org.springframework.beans.factory.annotation.Autowired
    private CollectionTaskManagementService management;
    public CollectionPipelineController(CollectionPipeline pipeline, CurrentUserProvider users) { this.pipeline = pipeline; this.users = users; }
    @GetMapping public ApiResponse<?> list() { return ApiResponse.success(pipeline.sources()); }
    @GetMapping("/overview") public ApiResponse<?> overview() {return ApiResponse.success(management.overview());}
    @GetMapping("/{code}/history") public ApiResponse<?> history(@PathVariable String code,@RequestParam String type,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) String status,@RequestParam(required=false) String keyword) {
        return ApiResponse.success(management.history(code,type,page,size,status,keyword));
    }
    @GetMapping("/batches/{batch}/rows-page") public ApiResponse<?> rowsPage(@PathVariable String batch,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(management.rows(batch,page,size));
    }
    public record EnabledRequest(Boolean enabled) {}
    @PutMapping("/{code}/enabled") public ApiResponse<?> enabled(@PathVariable String code,@RequestBody EnabledRequest request) {
        admin();
        if(request.enabled()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请指定启停状态");
        try {pipeline.setEnabled(code,request.enabled());return ApiResponse.success(Map.of("enabled",request.enabled()));}
        catch(NoSuchElementException e) {throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}
        catch(IllegalArgumentException e) {throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        catch(IllegalStateException e) {throw new ResponseStatusException(HttpStatus.CONFLICT,e.getMessage());}
    }
    @GetMapping("/script-catalog") public ApiResponse<?> catalog() { return ApiResponse.success(pipeline.scriptCatalog()); }
    @GetMapping("/categories") public ApiResponse<?> categories() { return ApiResponse.success(pipeline.categoryOptions()); }
    @GetMapping("/script-versions") public ApiResponse<?> versions() { return ApiResponse.success(pipeline.scriptVersions()); }
    @GetMapping("/{code}/worker-runs") public ApiResponse<?> runs(@PathVariable String code) { return ApiResponse.success(pipeline.workerRuns(code)); }
    @PostMapping public ApiResponse<?> save(@RequestBody CollectionSource config) {
        admin();
        try { pipeline.save(config); return ApiResponse.success(Map.of("saved", true)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
    }
    @PostMapping("/{code}/scan") public ApiResponse<?> scan(@PathVariable String code) {
        admin();
        try { return ApiResponse.success(pipeline.scan(code)); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
    }
    @DeleteMapping("/{code}") public ApiResponse<?> delete(@PathVariable String code) {
        admin();
        try { pipeline.delete(code); return ApiResponse.success(Map.of("deleted", true)); }
        catch (NoSuchElementException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage()); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
    }
    @GetMapping("/{code}/batches") public ApiResponse<?> batches(@PathVariable String code) { return ApiResponse.success(pipeline.batches(code)); }
    @GetMapping("/{code}/attempts") public ApiResponse<?> attempts(@PathVariable String code) { return ApiResponse.success(pipeline.attempts(code)); }
    @GetMapping("/batches/{batch}/rows") public ApiResponse<?> rows(@PathVariable String batch) { return ApiResponse.success(pipeline.rows(batch)); }
    private void admin() {
        if (users.currentUser().filter(user -> Integer.valueOf(1).equals(user.getIsAdmin())).isEmpty())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可管理采集任务");
    }
}
