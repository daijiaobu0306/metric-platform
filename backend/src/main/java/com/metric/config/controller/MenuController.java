package com.metric.config.controller;

import com.metric.shared.dto.ApiResponse;
import com.metric.security.entity.SysMenu;
import com.metric.security.repository.SysMenuRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    @Autowired
    private SysMenuRepository menuRepository;

    @GetMapping
    public ApiResponse<List<SysMenu>> getAllMenus() {
        return ApiResponse.success(menuRepository.findByStatusOrderBySortOrderAsc(1));
    }
}
