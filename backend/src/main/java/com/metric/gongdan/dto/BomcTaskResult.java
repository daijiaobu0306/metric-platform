package com.metric.gongdan.dto;

import lombok.Data;

/**
 * 省 BOMC 告警分析工单接口的通用调用结果。
 *
 * <p>BOMC 的 createTask / archiveTask / quashTask 返回结构一致：
 * <pre>
 * {"BOMCSerialNo":"1676841","status":"success","message":"接口调用成功!"}
 * </pre>
 * 判定规则（来自生产实测）：{@code BOMCSerialNo} 为 "-1" 时必定失败，非 -1 且 status=success 才算成功。
 */
@Data
public class BomcTaskResult {

    /** 是否调用成功（BOMC 业务成功 + 有有效工单号） */
    private boolean success;

    /** BOMC 返回的详细信息（失败时为报错信息） */
    private String message;

    /** BOMC 工单号（BOMCSerialNo），失败时可能为 -1 或空 */
    private String bomcSerialNo;

    public static BomcTaskResult success(String bomcSerialNo, String message) {
        BomcTaskResult result = new BomcTaskResult();
        result.success = true;
        result.bomcSerialNo = bomcSerialNo;
        result.message = message;
        return result;
    }

    public static BomcTaskResult fail(String message) {
        BomcTaskResult result = new BomcTaskResult();
        result.success = false;
        result.message = message;
        return result;
    }

    public static BomcTaskResult fail(String message, String bomcSerialNo) {
        BomcTaskResult result = fail(message);
        result.bomcSerialNo = bomcSerialNo;
        return result;
    }
}
