package com.evops.turnover;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 周转率取数：全部由订单事件流水 t_rental_order_event 关联订单/车辆推导，
 * 不读车辆当前状态，历史任意区间都能按同一口径重算。窗口半开 [start, end)。
 */
@Mapper
public interface TurnoverStatsMapper {

    /** 期内 RETURN（还车结算）事件 + 订单当前状态（REVERSED 剔除）与金额/网点 */
    @Select("<script>"
            + "SELECT o.id AS orderId, o.order_no AS orderNo, o.vehicle_id AS vehicleId,"
            + " v.plate_no AS plateNo, o.customer_name AS customerName, o.branch_code AS branchCode,"
            + " o.settle_amount AS settleAmount, o.status AS orderStatus, e.biz_time AS returnTime"
            + " FROM t_rental_order_event e"
            + " JOIN t_rental_order o ON o.id = e.order_id"
            + " JOIN t_vehicle v ON v.id = o.vehicle_id"
            + " WHERE e.event_type = 'RETURN'"
            + " AND e.biz_time &gt;= #{start} AND e.biz_time &lt; #{end}"
            + " <if test='branch != null'> AND o.branch_code = #{branch} </if>"
            + " ORDER BY e.biz_time, e.id"
            + "</script>")
    List<SettleRow> selectSettleRows(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end,
                                     @Param("branch") String branch);

    /** 期内 PICKUP/RETURN 车辆活动（Service 侧按车辆去重并计数） */
    @Select("<script>"
            + "SELECT o.vehicle_id AS vehicleId, v.plate_no AS plateNo,"
            + " e.event_type AS eventType, e.biz_time AS eventTime"
            + " FROM t_rental_order_event e"
            + " JOIN t_rental_order o ON o.id = e.order_id"
            + " JOIN t_vehicle v ON v.id = o.vehicle_id"
            + " WHERE e.event_type IN ('PICKUP','RETURN')"
            + " AND e.biz_time &gt;= #{start} AND e.biz_time &lt; #{end}"
            + " <if test='branch != null'> AND o.branch_code = #{branch} </if>"
            + " ORDER BY o.vehicle_id, e.biz_time, e.id"
            + "</script>")
    List<ActivityRow> selectActivityRows(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end,
                                         @Param("branch") String branch);
}
