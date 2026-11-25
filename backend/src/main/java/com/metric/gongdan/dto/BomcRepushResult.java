package com.metric.gongdan.dto;

import lombok.Data;

/**
 * 重新推送 BOMC 的调用结果。
 *
 * <p>重新推送走 createTask，会在 BOMC 新建工单并返回新工单号；本地也会
 * 新建一条工单承载新推送（原工单保留、不自动撤销）。
 */
@Data
public class BomcRepushResult {

    /** 是否调用成功 */
    private boolean success;

    /** 详细信息（失败时为报错原因） */
    private String message;

    /** BOMC 新建工单号 */
    private String bomcSerialNo;

    /** 本地新建工单的主键ID */
    private Long newLocalId;

    /** 本地新建工单编号（gongdanId） */
    private String newGongdanNo;

    public static BomcRepushResult success(String bomcSerialNo, Long newLocalId, String newGongdanNo, String message) {
        BomcRepushResult result = new BomcRepushResult();
        result.success = true;
        result.bomcSerialNo = bomcSerialNo;
        result.newLocalId = newLocalId;
        result.newGongdanNo = newGongdanNo;
        result.message = message;
        return result;
    }

    public static BomcRepushResult fail(String message) {
        BomcRepushResult result = new BomcRepushResult();
        result.success = false;
        result.message = message;
        return result;
    }
}
