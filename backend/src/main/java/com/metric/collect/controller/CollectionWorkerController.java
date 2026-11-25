package com.metric.collect.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.collect.service.CollectionWorkerService;
import com.metric.shared.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/collection/worker")
public class CollectionWorkerController {
    private final CollectionWorkerService workers;
    private final ObjectMapper mapper;
    public CollectionWorkerController(CollectionWorkerService workers,ObjectMapper mapper) {this.workers=workers;this.mapper=mapper;}
    private JsonNode body(HttpServletRequest request) throws java.io.IOException {
        byte[] bytes=request.getInputStream().readNBytes(10*1024*1024+1);
        if(bytes.length>10*1024*1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"批次超过10MB");
        var node=mapper.readTree(bytes);
        if(node==null || !node.isObject()) throw new IllegalArgumentException("请求须为JSON对象");
        return node;
    }
    @PostMapping("/claims") public ApiResponse<?> claim(HttpServletRequest request) throws java.io.IOException {
        return ApiResponse.success(workers.claim(body(request).path("workerId").asText()));
    }
    @PostMapping("/runs/{id}/heartbeat") public ApiResponse<?> heartbeat(@PathVariable String id,@RequestHeader("X-Collection-Lease") String token) {
        return ApiResponse.success(workers.heartbeat(id,token));
    }
    @PostMapping("/runs/{id}/batches") public ApiResponse<?> submit(@PathVariable String id,@RequestHeader("X-Collection-Lease") String token,HttpServletRequest request) throws java.io.IOException {
        return ApiResponse.success(workers.submit(id,token,body(request)));
    }
    @GetMapping("/runs/{id}/batches/{hash}") public ApiResponse<?> status(@PathVariable String id,@PathVariable String hash,
            @RequestHeader("X-Collection-Lease") String token,@RequestParam(required=false) String businessDate,
            @RequestParam(required=false) String sourceFile) {
        return ApiResponse.success(workers.status(id,token,hash,businessDate,sourceFile));
    }
    @PostMapping("/runs/{id}/archive") public ApiResponse<?> archive(@PathVariable String id,@RequestHeader("X-Collection-Lease") String token,HttpServletRequest request) throws java.io.IOException {
        return ApiResponse.success(workers.archived(id,token,body(request)));
    }
    @PostMapping("/runs/{id}/finish") public ApiResponse<?> finish(@PathVariable String id,@RequestHeader("X-Collection-Lease") String token,HttpServletRequest request) throws java.io.IOException {
        var node=body(request);
        String error=node.path("error").isNull() || node.path("error").isMissingNode()?null:node.path("error").asText();
        return ApiResponse.success(workers.finish(id,token,error));
    }
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST) public ApiResponse<?> invalid(Exception e) {return ApiResponse.error(400,e.getMessage());}
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT) public ApiResponse<?> conflict(Exception e) {return ApiResponse.error(409,e.getMessage());}
}
