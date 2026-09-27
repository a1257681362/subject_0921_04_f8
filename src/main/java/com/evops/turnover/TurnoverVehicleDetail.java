package com.evops.turnover;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 周转率分母明细：快照期内每台活跃（有 PICKUP/RETURN 事件）去重车辆一行。
 */
@TableName("t_turnover_vehicle_detail")
public class TurnoverVehicleDetail {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private Long vehicleId;
    private String plateNo;
    private Integer pickupCount;
    private Integer returnCount;
    private LocalDateTime firstHandoverTime;
    private LocalDateTime lastHandoverTime;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public Integer getPickupCount() { return pickupCount; }
    public void setPickupCount(Integer pickupCount) { this.pickupCount = pickupCount; }
    public Integer getReturnCount() { return returnCount; }
    public void setReturnCount(Integer returnCount) { this.returnCount = returnCount; }
    public LocalDateTime getFirstHandoverTime() { return firstHandoverTime; }
    public void setFirstHandoverTime(LocalDateTime firstHandoverTime) { this.firstHandoverTime = firstHandoverTime; }
    public LocalDateTime getLastHandoverTime() { return lastHandoverTime; }
    public void setLastHandoverTime(LocalDateTime lastHandoverTime) { this.lastHandoverTime = lastHandoverTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
