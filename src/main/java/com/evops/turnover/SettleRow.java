package com.evops.turnover;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 周转率取数行：期内 RETURN 事件关联的订单当前快照（状态/金额/网点）。
 * 完全由订单事件流水 + 订单当前状态推导，不依赖车辆当前状态。
 */
public class SettleRow {
    private Long orderId;
    private String orderNo;
    private Long vehicleId;
    private String plateNo;
    private String customerName;
    private String branchCode;
    private BigDecimal settleAmount;
    /** 订单当前状态：REVERSED 即剔除并披露 */
    private String orderStatus;
    private LocalDateTime returnTime;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public BigDecimal getSettleAmount() { return settleAmount; }
    public void setSettleAmount(BigDecimal settleAmount) { this.settleAmount = settleAmount; }
    public String getOrderStatus() { return orderStatus; }
    public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }
    public LocalDateTime getReturnTime() { return returnTime; }
    public void setReturnTime(LocalDateTime returnTime) { this.returnTime = returnTime; }
}
