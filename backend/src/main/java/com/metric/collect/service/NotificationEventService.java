package com.metric.collect.service;

import com.metric.collect.entity.NotificationEvent;
import com.metric.collect.repository.NotificationEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 全局通知事件服务 —— 写：被各业务事件 listener 调用；
 * 读：被前端 /api/notifications/recent 轮询消费，以及「历史通知」管理界面分页查询。
 */
@Service
public class NotificationEventService {

    @Autowired
    private NotificationEventRepository repository;

    // V47: 原始数据到达检测器（只按"原始数据有没有新进来、进来多少条"判断，与指标值无关）
    @Autowired
    private RawDataArrivalDetector rawDataArrivalDetector;

    /** 事件类型常量 */
    public static final String TYPE_DATA_COLLECTION = "DATA_COLLECTION";
    public static final String TYPE_ALARM = "ALARM";
    public static final String TYPE_ISSUE = "ISSUE";
    public static final String TYPE_GONGDAN = "GONGDAN";

    @Transactional
    public NotificationEvent record(String type, String title, String content, int count) {
        // V47: 「数据汇聚」通知只表达一件事 —— 原始数据表有没有新数据进来、进来多少条。
        // 调用方（指标监控调度）每分钟都会调一次，它关心的是指标计算；
        // 这里不看指标值/数据时间，只查原始数据入库表，避免把"存量指标被重算"当成汇聚。
        if (TYPE_DATA_COLLECTION.equals(type)) {
            RawDataArrivalDetector.ArrivalSummary summary = rawDataArrivalDetector.check();
            if (summary.isEmpty()) {
                return null;   // 没有原始数据进来，不落库、不通知
            }
            title = "数据汇聚 · 新增 " + summary.total() + " 条原始数据";
            content = summary.brief(RawDataArrivalDetector.MAX_SOURCE_ITEMS);
            count = (int) Math.min(summary.total(), Integer.MAX_VALUE);
        }
        NotificationEvent ev = new NotificationEvent();
        ev.setEventType(type);
        ev.setTitle(title);
        ev.setContent(content);
        ev.setRelatedCount(count);
        ev.setCreatedAt(LocalDateTime.now());
        return repository.save(ev);
    }

    public List<NotificationEvent> findRecentSince(Long sinceId, LocalDateTime loginTime, int limit) {
        return repository.findRecentSince(sinceId, loginTime, PageRequest.of(0, limit));
    }

    // ==================== 历史通知管理（V47） ====================

    /** 分页查询历史通知，按 id 倒序（最新在前）。page 从 1 开始。 */
    public Page<NotificationEvent> search(String eventType, String keyword,
                                          LocalDateTime startTime, LocalDateTime endTime,
                                          int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        PageRequest request = PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "id"));
        return repository.search(blankToNull(eventType), blankToNull(keyword), startTime, endTime, request);
    }

    /** 当前筛选条件下有多少条（用于"批量删除"前的二次确认文案）。 */
    public long countByFilter(String eventType, String keyword,
                              LocalDateTime startTime, LocalDateTime endTime) {
        return repository.countByFilter(blankToNull(eventType), blankToNull(keyword), startTime, endTime);
    }

    /** 删除单条，返回是否真的删掉了。 */
    @Transactional
    public boolean deleteById(Long id) {
        if (id == null) return false;
        Optional<NotificationEvent> found = repository.findById(id);
        if (found.isEmpty()) return false;
        repository.deleteById(id);
        return true;
    }

    /** 按 id 批量删除，返回删除条数。 */
    @Transactional
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return repository.deleteByIds(ids);
    }

    /**
     * 按条件清理历史通知。
     *
     * @param eventType 为空表示不限类型
     * @param deadline  为空表示不限时间；非空时删除 createdAt 早于该时间的记录
     * @return 删除条数
     */
    @Transactional
    public int cleanup(String eventType, LocalDateTime deadline) {
        return repository.deleteByFilter(blankToNull(eventType), deadline);
    }

    /** 某类型（或全部）的通知条数。 */
    public long countByType(String eventType) {
        return repository.countByType(blankToNull(eventType));
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
