package com.evops.turnover;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.turnover.dto.TurnoverConfirmRequest;
import com.evops.turnover.dto.TurnoverGenerateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 周转率统计服务（turnover-1）。
 *
 * 取数完全来自订单事件流水（见 TurnoverStatsMapper）：
 * - 分子：期内 RETURN 且订单当前非 REVERSED 的去重订单；REVERSED 订单落明细 included=FALSE 并在头部披露；
 * - 分母：期内有 PICKUP/RETURN 的去重车辆；
 * - 比率：分子/分母，四位小数 HALF_UP，分母为 0 记 0。
 *
 * 同口径（周期类型+起点+网点）唯一：GENERATED 重算删除旧明细后覆盖（快照号不变、可追溯同一口径），
 * CONFIRMED 拒绝重算与任何改写（审计冻结）。
 */
@Service
public class TurnoverService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String WHOLE_COMPANY = "*";
    private static final String REVERSED = "REVERSED";

    private final TurnoverSnapshotMapper snapshotMapper;
    private final TurnoverOrderDetailMapper orderDetailMapper;
    private final TurnoverVehicleDetailMapper vehicleDetailMapper;
    private final TurnoverStatsMapper statsMapper;

    public TurnoverService(TurnoverSnapshotMapper snapshotMapper,
                           TurnoverOrderDetailMapper orderDetailMapper,
                           TurnoverVehicleDetailMapper vehicleDetailMapper,
                           TurnoverStatsMapper statsMapper) {
        this.snapshotMapper = snapshotMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.vehicleDetailMapper = vehicleDetailMapper;
        this.statsMapper = statsMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public TurnoverSnapshot generate(TurnoverGenerateRequest request) {
        String periodType = TurnoverPeriod.normalize(request.getPeriodType());
        LocalDateTime[] range = TurnoverPeriod.range(periodType, request.getBizDate());
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];
        String scopeBranch = normalizeScopeBranch(request.getBranchCode());
        String sqlBranch = WHOLE_COMPANY.equals(scopeBranch) ? null : scopeBranch;

        TurnoverSnapshot existing = snapshotMapper.selectOne(new LambdaQueryWrapper<TurnoverSnapshot>()
                .eq(TurnoverSnapshot::getPeriodType, periodType)
                .eq(TurnoverSnapshot::getPeriodStart, start)
                .eq(TurnoverSnapshot::getBranchCode, scopeBranch));
        if (existing != null && TurnoverSnapshot.STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw new BizException("周转率快照已确认冻结，不允许重算：" + existing.getSnapshotNo());
        }

        // 分子：期内结算事件 -> 按订单去重（正常一单一次 RETURN，去重为审计兜底）
        List<SettleRow> settleRows = statsMapper.selectSettleRows(start, end, sqlBranch);
        Map<Long, SettleRow> settleByOrder = new LinkedHashMap<>();
        for (SettleRow row : settleRows) {
            settleByOrder.putIfAbsent(row.getOrderId(), row);
        }
        int turnoverCount = 0;
        int reversedExcluded = 0;
        List<TurnoverOrderDetail> orderDetails = new ArrayList<>();
        for (SettleRow row : settleByOrder.values()) {
            boolean included = !REVERSED.equals(row.getOrderStatus());
            if (included) {
                turnoverCount++;
            } else {
                reversedExcluded++;
            }
            orderDetails.add(toOrderDetail(row, included));
        }

        // 分母：期内车辆活动 -> 按车辆去重并计数
        List<ActivityRow> activityRows = statsMapper.selectActivityRows(start, end, sqlBranch);
        Map<Long, VehicleAgg> activityByVehicle = new LinkedHashMap<>();
        for (ActivityRow row : activityRows) {
            VehicleAgg agg = activityByVehicle.computeIfAbsent(row.getVehicleId(),
                    k -> new VehicleAgg(row.getVehicleId(), row.getPlateNo()));
            agg.accept(row);
        }
        List<TurnoverVehicleDetail> vehicleDetails = new ArrayList<>();
        for (VehicleAgg agg : activityByVehicle.values()) {
            vehicleDetails.add(agg.toDetail());
        }
        int activeVehicles = activityByVehicle.size();

        BigDecimal rate = activeVehicles == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : new BigDecimal(turnoverCount).divide(new BigDecimal(activeVehicles), 4, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();
        TurnoverSnapshot snapshot;
        if (existing == null) {
            snapshot = new TurnoverSnapshot();
            snapshot.setSnapshotNo("TV" + now.format(NO_FMT)
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
            snapshot.setPeriodType(periodType);
            snapshot.setPeriodStart(start);
            snapshot.setPeriodEnd(end);
            snapshot.setBranchCode(scopeBranch);
            snapshot.setStatus(TurnoverSnapshot.STATUS_GENERATED);
            applyCounts(snapshot, turnoverCount, reversedExcluded, activeVehicles, rate);
            snapshotMapper.insert(snapshot);
        } else {
            // 重算：旧明细先清空再按最新口径落行；头部计数覆盖，状态回到 GENERATED、确认信息抹除
            orderDetailMapper.delete(new LambdaQueryWrapper<TurnoverOrderDetail>()
                    .eq(TurnoverOrderDetail::getSnapshotId, existing.getId()));
            vehicleDetailMapper.delete(new LambdaQueryWrapper<TurnoverVehicleDetail>()
                    .eq(TurnoverVehicleDetail::getSnapshotId, existing.getId()));
            snapshot = existing;
            applyCounts(snapshot, turnoverCount, reversedExcluded, activeVehicles, rate);
            snapshot.setStatus(TurnoverSnapshot.STATUS_GENERATED);
            snapshot.setConfirmedBy(null);
            snapshot.setConfirmedTime(null);
            snapshot.setConfirmRemark(null);
            snapshotMapper.updateById(snapshot);
        }

        for (TurnoverOrderDetail detail : orderDetails) {
            detail.setSnapshotId(snapshot.getId());
            detail.setCreateTime(now);
            orderDetailMapper.insert(detail);
        }
        for (TurnoverVehicleDetail detail : vehicleDetails) {
            detail.setSnapshotId(snapshot.getId());
            detail.setCreateTime(now);
            vehicleDetailMapper.insert(detail);
        }
        return snapshot;
    }

    @Transactional(rollbackFor = Exception.class)
    public TurnoverSnapshot confirm(Long id, TurnoverConfirmRequest request) {
        TurnoverSnapshot snapshot = loadSnapshot(id);
        if (TurnoverSnapshot.STATUS_CONFIRMED.equals(snapshot.getStatus())) {
            throw new BizException("周转率快照已确认，不能重复确认：" + snapshot.getSnapshotNo());
        }
        snapshot.setStatus(TurnoverSnapshot.STATUS_CONFIRMED);
        snapshot.setConfirmedBy(RequestContext.operator());
        snapshot.setConfirmedTime(LocalDateTime.now());
        snapshot.setConfirmRemark(request == null ? null : request.getRemark());
        snapshotMapper.updateById(snapshot);
        return snapshot;
    }

    public TurnoverSnapshot loadSnapshot(Long id) {
        TurnoverSnapshot snapshot = snapshotMapper.selectById(id);
        if (snapshot == null) {
            throw new BizException("周转率快照不存在：" + id);
        }
        return snapshot;
    }

    public List<TurnoverSnapshot> listSnapshots(String periodType, String branchCode, String status) {
        String type = periodType != null && !periodType.trim().isEmpty()
                ? TurnoverPeriod.normalize(periodType) : null;
        String branch = branchCode != null && !branchCode.trim().isEmpty() ? branchCode.trim() : null;
        String st = status != null && !status.trim().isEmpty() ? status.trim().toUpperCase() : null;
        LambdaQueryWrapper<TurnoverSnapshot> wrapper = new LambdaQueryWrapper<TurnoverSnapshot>()
                .eq(type != null, TurnoverSnapshot::getPeriodType, type)
                .eq(branch != null, TurnoverSnapshot::getBranchCode, branch)
                .eq(st != null, TurnoverSnapshot::getStatus, st)
                .orderByDesc(TurnoverSnapshot::getPeriodStart)
                .orderByDesc(TurnoverSnapshot::getId);
        return snapshotMapper.selectList(wrapper);
    }

    public List<TurnoverOrderDetail> listOrderDetails(Long snapshotId) {
        return orderDetailMapper.selectList(new LambdaQueryWrapper<TurnoverOrderDetail>()
                .eq(TurnoverOrderDetail::getSnapshotId, snapshotId)
                .orderByDesc(TurnoverOrderDetail::getIncluded)
                .orderByAsc(TurnoverOrderDetail::getReturnTime)
                .orderByAsc(TurnoverOrderDetail::getId));
    }

    public List<TurnoverVehicleDetail> listVehicleDetails(Long snapshotId) {
        return vehicleDetailMapper.selectList(new LambdaQueryWrapper<TurnoverVehicleDetail>()
                .eq(TurnoverVehicleDetail::getSnapshotId, snapshotId)
                .orderByAsc(TurnoverVehicleDetail::getId));
    }

    private void applyCounts(TurnoverSnapshot snapshot, int turnoverCount, int reversedExcluded,
                             int activeVehicles, BigDecimal rate) {
        snapshot.setTurnoverCount(turnoverCount);
        snapshot.setReversedExcludedCount(reversedExcluded);
        snapshot.setActiveVehicleCount(activeVehicles);
        snapshot.setTurnoverRate(rate);
    }

    private TurnoverOrderDetail toOrderDetail(SettleRow row, boolean included) {
        TurnoverOrderDetail detail = new TurnoverOrderDetail();
        detail.setOrderId(row.getOrderId());
        detail.setOrderNo(row.getOrderNo());
        detail.setVehicleId(row.getVehicleId());
        detail.setPlateNo(row.getPlateNo());
        detail.setCustomerName(row.getCustomerName());
        detail.setBranchCode(row.getBranchCode());
        detail.setSettleAmount(row.getSettleAmount());
        detail.setReturnTime(row.getReturnTime());
        detail.setIncluded(included);
        return detail;
    }

    private String normalizeScopeBranch(String branchCode) {
        if (branchCode == null || branchCode.trim().isEmpty() || "*".equals(branchCode.trim())) {
            return WHOLE_COMPANY;
        }
        return branchCode.trim();
    }

    /** 单车活动聚合（分母明细）。 */
    private static final class VehicleAgg {
        private final Long vehicleId;
        private final String plateNo;
        private int pickupCount;
        private int returnCount;
        private LocalDateTime first;
        private LocalDateTime last;

        VehicleAgg(Long vehicleId, String plateNo) {
            this.vehicleId = vehicleId;
            this.plateNo = plateNo;
        }

        void accept(ActivityRow row) {
            if ("PICKUP".equals(row.getEventType())) {
                pickupCount++;
            } else if ("RETURN".equals(row.getEventType())) {
                returnCount++;
            }
            LocalDateTime time = row.getEventTime();
            if (first == null || time.isBefore(first)) {
                first = time;
            }
            if (last == null || time.isAfter(last)) {
                last = time;
            }
        }

        TurnoverVehicleDetail toDetail() {
            TurnoverVehicleDetail detail = new TurnoverVehicleDetail();
            detail.setVehicleId(vehicleId);
            detail.setPlateNo(plateNo);
            detail.setPickupCount(pickupCount);
            detail.setReturnCount(returnCount);
            detail.setFirstHandoverTime(first);
            detail.setLastHandoverTime(last);
            return detail;
        }
    }
}
