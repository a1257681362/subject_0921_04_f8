package com.evops.turnover;

import java.time.LocalDateTime;

/**
 * 周转率取数行：期内 PICKUP/RETURN 事件对应的车辆活动（在 Service 侧按车辆去重聚合计数）。
 */
public class ActivityRow {
    private Long vehicleId;
    private String plateNo;
    /** PICKUP / RETURN */
    private String eventType;
    private LocalDateTime eventTime;

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
}
