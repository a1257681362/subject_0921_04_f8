package com.evops.turnover;

import com.evops.common.ApiResponse;
import com.evops.turnover.dto.TurnoverConfirmRequest;
import com.evops.turnover.dto.TurnoverGenerateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周转率统计（turnover-1）。快照可重算（GENERATED）、确认后冻结（CONFIRMED），
 * 分子分母明细逐单逐车可勾稽，数字经得起审计。
 */
@RestController
@RequestMapping("/api/turnover")
public class TurnoverController {

    private final TurnoverService turnoverService;

    public TurnoverController(TurnoverService turnoverService) {
        this.turnoverService = turnoverService;
    }

    /** 出表/重算：periodType=DAY/WEEK/MONTH，bizDate 为周期内任意一天，branchCode 缺省全公司 */
    @PostMapping("/snapshots")
    public ApiResponse<TurnoverSnapshot> generate(@Valid @RequestBody TurnoverGenerateRequest request) {
        return ApiResponse.ok(turnoverService.generate(request));
    }

    /** 快照列表：periodType / branchCode / status 可选过滤 */
    @GetMapping("/snapshots")
    public ApiResponse<List<TurnoverSnapshot>> list(
            @RequestParam(required = false) String periodType,
            @RequestParam(required = false) String branchCode,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(turnoverService.listSnapshots(periodType, branchCode, status));
    }

    /** 快照详情：头部口径 + 计数；分子分母明细行数一并返回便于勾稽 */
    @GetMapping("/snapshots/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        TurnoverSnapshot snapshot = turnoverService.loadSnapshot(id);
        List<TurnoverOrderDetail> orders = turnoverService.listOrderDetails(id);
        List<TurnoverVehicleDetail> vehicles = turnoverService.listVehicleDetails(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("snapshot", snapshot);
        data.put("orderDetailCount", orders.size());
        data.put("vehicleDetailCount", vehicles.size());
        return ApiResponse.ok(data);
    }

    /** 分子明细：期内每笔结算订单一行（included=false 为已冲销剔除行） */
    @GetMapping("/snapshots/{id}/orders")
    public ApiResponse<List<TurnoverOrderDetail>> orderDetails(@PathVariable Long id) {
        turnoverService.loadSnapshot(id);
        return ApiResponse.ok(turnoverService.listOrderDetails(id));
    }

    /** 分母明细：期内每台活跃车辆一行 */
    @GetMapping("/snapshots/{id}/vehicles")
    public ApiResponse<List<TurnoverVehicleDetail>> vehicleDetails(@PathVariable Long id) {
        turnoverService.loadSnapshot(id);
        return ApiResponse.ok(turnoverService.listVehicleDetails(id));
    }

    /** 确认冻结：确认后拒绝重算（审计冻结点） */
    @PostMapping("/snapshots/{id}/confirm")
    public ApiResponse<TurnoverSnapshot> confirm(@PathVariable Long id,
                                                 @RequestBody(required = false) TurnoverConfirmRequest request) {
        return ApiResponse.ok(turnoverService.confirm(id, request));
    }
}
