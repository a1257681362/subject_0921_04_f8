package com.evops.auth.dto;

import javax.validation.constraints.NotBlank;

public class UserStatusRequest {

    /** ENABLED / DISABLED */
    @NotBlank(message = "目标状态不能为空")
    private String status;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
