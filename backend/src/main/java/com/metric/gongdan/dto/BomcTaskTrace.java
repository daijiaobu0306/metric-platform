package com.metric.gongdan.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 省 BOMC 透明化查询（queryTask）返回的处理轨迹。
 *
 * <p>对应接口规范 4.7 的返回结构：{@code data.current}（当前节点）+ {@code data.history}（历史节点）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BomcTaskTrace {

    /** 查询是否成功（BOMC status=success 且返回了 data） */
    private boolean success;

    /** 失败时的报错信息 */
    private String message;

    /** 查询的 BOMC 工单号 */
    private String bomcSerialNo;

    /** 当前节点信息 */
    private Node current;

    /** 历史节点列表（按 sortData 升序） */
    private List<History> history = new ArrayList<>();

    /** 当前节点 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Node {
        /** 节点名称，如"确认下发" */
        private String nodeName;
        /** 节点ID */
        private String nodeId;
        /** 超时时间，节点未指定则为空 */
        @JsonProperty("data_over_time")
        private String dataOverTime;
        /** 处理人ID */
        private String userId;
        /** 处理人名称 */
        private String userName;
        /** 所属角色ID */
        private String roleId;
        /** 角色名称 */
        private String roleName;
    }

    /** 历史节点 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class History {
        /** 节点名称 */
        private String nodeName;
        /** 节点ID */
        private String nodeId;
        /** 动作名称 */
        private String actionName;
        /** 处理人ID */
        private String dealedById;
        /** 处理人名称 */
        private String dealedBy;
        /** 数据ID */
        private String dataId;
        /** 处理时间，当前节点未处理时为空 */
        private String lastUpdate;
        /** 处理人电话 */
        private String mobile;
        /** 处理意见 */
        @JsonProperty("task_dealed_idea")
        private String taskDealedIdea;
        /** 排序（毫秒时间戳） */
        private Long sortData;
    }
}
