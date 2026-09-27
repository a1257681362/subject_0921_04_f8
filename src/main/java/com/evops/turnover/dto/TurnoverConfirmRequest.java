package com.evops.turnover.dto;

import javax.validation.constraints.Size;

public class TurnoverConfirmRequest {

    @Size(max = 500, message = "确认备注长度不能超过 500")
    private String remark;

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
