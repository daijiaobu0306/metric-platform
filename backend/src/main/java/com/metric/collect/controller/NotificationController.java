package com.metric.collect.controller;

import com.metric.collect.entity.NotificationEvent;
import com.metric.collect.service.NotificationEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局通知事件接口。
 *
 * <p>两类消费方：
 * <ul>
 *   <li>右上角浮层：每 5 秒轮询 {@code /recent?sinceId=N}，按登录时间过滤，只看本次上线之后的事件；</li>
 *   <li>历史通知管理：{@code /page} 分页查询全量历史，配套单条删除 / 批量删除 / 按条件清理。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    /** SessionKeyAuthenticationFilter 把当前会话登录时间写到 request 属性名 */
    public static final String ATTR_LOGIN_TIME = "currentSessionLoginTime";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private NotificationEventService service;

    /** 浮层轮询：登录之后的新事件（按 id 升序） */
    @GetMapping("/recent")
    public Map<String, Object> recent(
            @RequestParam(name = "sinceId", required = false, defaultValue = "0") Long sinceId,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            HttpServletRequest request) {
        LocalDateTime loginTime = (LocalDateTime) request.getAttribute(ATTR_LOGIN_TIME);
        if (loginTime == null) {
            // filter 没设置说明没登录态；安全默认值 = 当前时间之后（拿到空集即可）
            loginTime = LocalDateTime.now();
        }
        Map<String, Object> result = new HashMap<>();
        List<NotificationEvent> events = service.findRecentSince(sinceId, loginTime, Math.min(limit, 50));
        result.put("code", 200);
        result.put("data", events);
        result.put("count", events.size());
        return result;
    }

    /**
     * 历史通知分页查询。
     *
     * @param eventType 类型过滤（DATA_COLLECTION/ALARM/GONGDAN/ISSUE），空表示全部
     * @param keyword   关键字，模糊匹配标题与正文
     * @param startTime 起始时间 "yyyy-MM-dd HH:mm:ss"
     * @param endTime   结束时间 "yyyy-MM-dd HH:mm:ss"
     * @param page      页码，从 1 开始
     * @param size      每页条数，最大 200
     */
    @GetMapping("/page")
    public Map<String, Object> page(
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "startTime", required = false) String startTime,
            @RequestParam(name = "endTime", required = false) String endTime,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {

        LocalDateTime start = parse(startTime);
        LocalDateTime end = parse(endTime);
        Page<NotificationEvent> result = service.search(eventType, keyword, start, end, page, size);

        Map<String, Object> data = new HashMap<>();
        data.put("records", result.getContent());
        data.put("total", result.getTotalElements());
        data.put("page", page);
        data.put("size", size);
        data.put("pages", result.getTotalPages());
        // 便于"批量删除当前筛选结果"时给出准确条数
        data.put("filteredTotal", service.countByFilter(eventType, keyword, start, end));

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", data);
        return response;
    }

    /** 删除单条历史通知 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") Long id) {
        boolean removed = service.deleteById(id);
        Map<String, Object> response = new HashMap<>();
        response.put("code", removed ? 200 : 404);
        response.put("message", removed ? "已删除" : "通知不存在");
        response.put("data", removed);
        return response;
    }

    /** 批量删除（勾选） */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody List<Long> ids) {
        int deleted = service.deleteByIds(ids);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", deleted);
        response.put("message", "已删除 " + deleted + " 条通知");
        return response;
    }

    /**
     * 按条件清理。
     *
     * <p>安全约定：必须显式给出清理条件，避免"空条件 = 删库"这种误操作。
     * <ul>
     *   <li>{@code all=true}：清空全部（前端必须二次确认）；</li>
     *   <li>{@code eventType}：清空该类型；</li>
     *   <li>{@code beforeDays}：只清理 N 天前的记录。</li>
     * </ul>
     * 三者都不给时直接拒绝，不执行任何删除。
     */
    @PostMapping("/cleanup")
    public Map<String, Object> cleanup(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new HashMap<>() : body;
        String eventType = payload.get("eventType") == null ? null : String.valueOf(payload.get("eventType"));
        boolean all = Boolean.TRUE.equals(payload.get("all"));

        LocalDateTime deadline = null;
        Object beforeDays = payload.get("beforeDays");
        if (!all && beforeDays != null && !String.valueOf(beforeDays).isBlank()) {
            try {
                int days = Integer.parseInt(String.valueOf(beforeDays));
                if (days > 0) deadline = LocalDateTime.now().minusDays(days);
            } catch (NumberFormatException ignored) {
                // 参数不合法时按"不限时间"处理
            }
        }
        if (all) {
            eventType = null;
            deadline = null;
        }

        // 关键保护：没有任何条件时拒绝执行，防止空请求把整张通知表清空
        if (!all && (eventType == null || eventType.isBlank()) && deadline == null) {
            Map<String, Object> reject = new HashMap<>();
            reject.put("code", 400);
            reject.put("message", "请先指定清理条件（全部 / 某个类型 / N 天前）");
            reject.put("data", 0);
            return reject;
        }

        int deleted = service.cleanup(eventType, deadline);
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", deleted);
        response.put("message", "已清理 " + deleted + " 条通知");
        return response;
    }

    /** 各类型通知条数，用于管理页顶部统计 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String type : new String[]{
                NotificationEventService.TYPE_DATA_COLLECTION,
                NotificationEventService.TYPE_ALARM,
                NotificationEventService.TYPE_GONGDAN,
                NotificationEventService.TYPE_ISSUE}) {
            Map<String, Object> item = new HashMap<>();
            item.put("eventType", type);
            item.put("count", service.countByType(type));
            items.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        data.put("total", service.countByType(null));

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", data);
        return response;
    }

    private static LocalDateTime parse(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.trim();
        try {
            if (value.length() == 10) {
                return LocalDateTime.parse(value + " 00:00:00", DATE_TIME);
            }
            if (value.length() == 16) {
                return LocalDateTime.parse(value + ":00", DATE_TIME);
            }
            return LocalDateTime.parse(value, DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }
}
