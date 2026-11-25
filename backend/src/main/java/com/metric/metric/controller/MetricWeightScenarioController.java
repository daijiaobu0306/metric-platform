package com.metric.metric.controller;

import com.metric.metric.service.MetricWeightScenarioService;
import com.metric.metric.service.MetricWeightScenarioService.Save;
import com.metric.security.service.CurrentUserProvider;
import com.metric.shared.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/weight/scenarios")
@RequiredArgsConstructor
public class MetricWeightScenarioController {
    private final MetricWeightScenarioService service;
    private final CurrentUserProvider users;

    @GetMapping
    public ApiResponse<?> list() {
        requireAdmin();
        return ApiResponse.success(service.list());
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Save request) {
        return ApiResponse.success(service.save(null, request, requireAdmin()));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> edit(@PathVariable long id, @RequestBody Save request) {
        return ApiResponse.success(service.save(id, request, requireAdmin()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> scenarioError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(ApiResponse.error(error.getStatusCode().value(), error.getReason()));
    }

    private long requireAdmin() {
        return users.currentUser().filter(user -> Integer.valueOf(1).equals(user.getIsAdmin()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可管理业务场景")).getId();
    }
}
