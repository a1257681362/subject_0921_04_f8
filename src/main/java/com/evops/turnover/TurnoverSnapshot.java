package com.evops.turnover;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 周转率统计快照（turnover-1）。
 *
 * 口径（区间 [periodStart, periodEnd)，branchCode='*' 为全公司）：
 * - turnoverCount 分子：期内发生 RETURN 且订单当前未被冲销的去重订单数；
 * - reversedExcludedCount：期内结算但已冲销、被剔除并单独披露的订单数；
 * - activeVehicleCount 分母：期内发生过 PICKUP/RETURN 的去重车辆数；
 * - turnoverRate = 分子 / 分母（分母 0 记 0）。
 *
 * GENERATED 可同口径重算覆盖；CONFIRMED 后冻结。同口径（周期类型+起点+网点）唯一。
 */
@TableName("t_turnover_snapshot")
public class TurnoverSnapshot extends BizEntity {

    public static final String STATUS_GENERATED = "GENERATED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    private String snapshotNo;
    /** DAY / WEEK / MONTH */
    private String periodType;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    /** '*' = 全公司 */
    private String branchCode;
    private Integer turnoverCount;
    private Integer reversedExcludedCount;
    private Integer activeVehicleCount;
    private BigDecimal turnoverRate;
    /** GENERATED / CONFIRMED */
    private String status;
    private String confirmedBy;
    private LocalDateTime confirmedTime;
    private String confirmRemark;

    public String getSnapshotNo() { return snapshotNo; }
    public void setSnapshotNo(String snapshotNo) { this.snapshotNo = snapshotNo; }
    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }
    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }
    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public Integer getTurnoverCount() { return turnoverCount; }
    public void setTurnoverCount(Integer turnoverCount) { this.turnoverCount = turnoverCount; }
    public Integer getReversedExcludedCount() { return reversedExcludedCount; }
    public void setReversedExcludedCount(Integer reversedExcludedCount) { this.reversedExcludedCount = reversedExcludedCount; }
    public Integer getActiveVehicleCount() { return activeVehicleCount; }
    public void setActiveVehicleCount(Integer activeVehicleCount) { this.activeVehicleCount = activeVehicleCount; }
    public BigDecimal getTurnoverRate() { return turnoverRate; }
    public void setTurnoverRate(BigDecimal turnoverRate) { this.turnoverRate = turnoverRate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
    public LocalDateTime getConfirmedTime() { return confirmedTime; }
    public void setConfirmedTime(LocalDateTime confirmedTime) { this.confirmedTime = confirmedTime; }
    public String getConfirmRemark() { return confirmRemark; }
    public void setConfirmRemark(String confirmRemark) { this.confirmRemark = confirmRemark; }
}
