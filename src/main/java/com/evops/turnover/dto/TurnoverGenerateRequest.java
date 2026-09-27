package com.evops.turnover.dto;

import javax.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 周转率出表/重算请求。periodType=DAY/WEEK/MONTH；bizDate 为周期内任意一天（缺省当天）；
 * branchCode 为空或 '*' 为全公司，否则仅该网点。
 */
public class TurnoverGenerateRequest {

    private String periodType;
    private LocalDate bizDate;

    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
}
