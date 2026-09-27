package com.evops.turnover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 周转率统计集成测试（turnover-1）：内存 H2 全链路。
 * 场景（业务日 2026-09-15，周二）：
 * - 车辆 A（网点 B01）：两单均在 09-15 还车结算（周转 2 次）；
 * - 车辆 B（网点 B02）：09-15 还车结算后被冲销（剔除并披露）；另有一单 09-16 还车（出窗口）；
 * - 车辆 C：无任何交接（不计入分母）。
 * 期望（DAY 09-15）：全公司 分子=2、剔除=1、分母=2、比率=1.0000；
 * B01 分子=2、分母=1、比率=2.0000；B02 分子=0、剔除=1、分母=1、比率=0。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:turnover-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.auth.enabled=false",
        "evops.recon.enabled=false"
})
class TurnoverIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 内存库 DB_CLOSE_DELAY=-1 在同 JVM 内跨方法存活，每方法先清空相关业务表保证计数隔离。 */
    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("TRUNCATE TABLE t_turnover_order_detail");
        jdbcTemplate.execute("TRUNCATE TABLE t_turnover_vehicle_detail");
        jdbcTemplate.execute("TRUNCATE TABLE t_turnover_snapshot");
        jdbcTemplate.execute("TRUNCATE TABLE t_rental_order_transition");
        jdbcTemplate.execute("TRUNCATE TABLE t_rental_order_event");
        jdbcTemplate.execute("TRUNCATE TABLE t_rental_handover");
        jdbcTemplate.execute("TRUNCATE TABLE t_rental_order_name_index");
        jdbcTemplate.execute("TRUNCATE TABLE t_rental_order");
        jdbcTemplate.execute("TRUNCATE TABLE t_vehicle");
    }

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder builder, String bizTime) {
        return builder.header("X-Operator", "tester")
                .header("X-Request-No", reqNo())
                .header("X-Biz-Time", bizTime)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private long createVehicle(String plateNo) throws Exception {
        MvcResult result = mockMvc.perform(write(post("/api/vehicles"), "2026-09-10T09:00:00")
                        .content("{\"plateNo\":\"" + plateNo + "\",\"model\":\"M1\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long vehicleId, String branchCode, String bizTime) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"vehicleId\":" + vehicleId
                + ",\"customerName\":\"张三\",\"branchCode\":\"" + branchCode + "\"}";
        MvcResult result = mockMvc.perform(write(post("/api/orders"), bizTime).content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    private void pickup(long orderId, String bizTime) throws Exception {
        mockMvc.perform(write(post("/api/orders/" + orderId + "/pickup"), bizTime)
                        .content("{\"fuelReading\":80,\"damagePhotos\":\"oss://p/a.jpg\"}"))
                .andExpect(status().isOk());
    }

    private void returnVehicle(long orderId, String bizTime, String amount) throws Exception {
        mockMvc.perform(write(post("/api/orders/" + orderId + "/return"), bizTime)
                        .content("{\"fuelReading\":50,\"damagePhotos\":\"oss://p/b.jpg\","
                                + "\"settleAmount\":" + amount + "}"))
                .andExpect(status().isOk());
    }

    private void reverse(long orderId, String bizTime) throws Exception {
        mockMvc.perform(write(post("/api/orders/" + orderId + "/reverse"), bizTime)
                        .content("{\"reason\":\"金额录错，冲销重开\"}"))
                .andExpect(status().isOk());
    }

    private long generate(String periodType, String bizDate, String branchCode) throws Exception {
        String body = "{\"periodType\":\"" + periodType + "\",\"bizDate\":\"" + bizDate + "\""
                + (branchCode == null ? "" : ",\"branchCode\":\"" + branchCode + "\"") + "}";
        MvcResult result = mockMvc.perform(write(post("/api/turnover/snapshots"), "2026-09-16T09:00:00")
                        .content(body))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    /** 搭建固定场景，返回车辆A/B/C 与 订单1/2/3/4 的 id */
    private long[] fixture() throws Exception {
        long vehicleA = createVehicle("京TA-" + reqNo().substring(0, 6));
        long vehicleB = createVehicle("京TB-" + reqNo().substring(0, 6));
        long vehicleC = createVehicle("京TC-" + reqNo().substring(0, 6));

        long order1 = createOrder("RO-T1-" + reqNo().substring(0, 6), vehicleA, "B01", "2026-09-15T08:00:00");
        pickup(order1, "2026-09-15T09:00:00");
        returnVehicle(order1, "2026-09-15T18:00:00", "300.00");

        long order2 = createOrder("RO-T2-" + reqNo().substring(0, 6), vehicleA, "B01", "2026-09-15T19:00:00");
        pickup(order2, "2026-09-15T19:30:00");
        returnVehicle(order2, "2026-09-15T23:00:00", "320.00");

        long order3 = createOrder("RO-T3-" + reqNo().substring(0, 6), vehicleB, "B02", "2026-09-15T10:00:00");
        pickup(order3, "2026-09-15T10:30:00");
        returnVehicle(order3, "2026-09-15T20:00:00", "500.00");
        reverse(order3, "2026-09-15T21:00:00");

        long order4 = createOrder("RO-T4-" + reqNo().substring(0, 6), vehicleB, "B02", "2026-09-16T08:00:00");
        pickup(order4, "2026-09-16T09:00:00");
        returnVehicle(order4, "2026-09-16T18:00:00", "260.00");

        return new long[]{vehicleA, vehicleB, vehicleC, order1, order2, order3, order4};
    }

    @Test
    void daySnapshotWholeCompanyCountsAndDetails() throws Exception {
        long[] ids = fixture();
        long snapshotId = generate("DAY", "2026-09-15", null);

        mockMvc.perform(get("/api/turnover/snapshots/" + snapshotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.periodType").value("DAY"))
                .andExpect(jsonPath("$.data.snapshot.branchCode").value("*"))
                .andExpect(jsonPath("$.data.snapshot.turnoverCount").value(2))
                .andExpect(jsonPath("$.data.snapshot.reversedExcludedCount").value(1))
                .andExpect(jsonPath("$.data.snapshot.activeVehicleCount").value(2))
                .andExpect(jsonPath("$.data.snapshot.turnoverRate").value(1.0))
                .andExpect(jsonPath("$.data.snapshot.status").value("GENERATED"))
                .andExpect(jsonPath("$.data.orderDetailCount").value(3))
                .andExpect(jsonPath("$.data.vehicleDetailCount").value(2));

        // 分子明细：3 行，冲销单 included=false 单独可见
        JsonNode orders = objectMapper.readTree(mockMvc.perform(
                        get("/api/turnover/snapshots/" + snapshotId + "/orders"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("data");
        org.junit.jupiter.api.Assertions.assertEquals(3, orders.size());
        int included = 0;
        int excluded = 0;
        for (JsonNode row : orders) {
            if (row.get("included").asBoolean()) {
                included++;
            } else {
                excluded++;
                org.junit.jupiter.api.Assertions.assertEquals(ids[5], row.get("orderId").asLong());
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(2, included);
        org.junit.jupiter.api.Assertions.assertEquals(1, excluded);

        // 分母明细：A、B 各一行，C 不在列
        JsonNode vehicles = objectMapper.readTree(mockMvc.perform(
                        get("/api/turnover/snapshots/" + snapshotId + "/vehicles"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("data");
        org.junit.jupiter.api.Assertions.assertEquals(2, vehicles.size());
        for (JsonNode row : vehicles) {
            long vid = row.get("vehicleId").asLong();
            org.junit.jupiter.api.Assertions.assertTrue(vid == ids[0] || vid == ids[1]);
        }
    }

    @Test
    void branchScopeAndWeekMonthPeriods() throws Exception {
        fixture();

        // 网点 B01：2 次周转 / 1 台活跃车
        long b01 = generate("DAY", "2026-09-15", "B01");
        mockMvc.perform(get("/api/turnover/snapshots/" + b01))
                .andExpect(jsonPath("$.data.snapshot.turnoverCount").value(2))
                .andExpect(jsonPath("$.data.snapshot.reversedExcludedCount").value(0))
                .andExpect(jsonPath("$.data.snapshot.activeVehicleCount").value(1))
                .andExpect(jsonPath("$.data.snapshot.turnoverRate").value(2.0));

        // 网点 B02：结算单被冲销，分子 0，剔除 1，分母 1
        long b02 = generate("DAY", "2026-09-15", "B02");
        mockMvc.perform(get("/api/turnover/snapshots/" + b02))
                .andExpect(jsonPath("$.data.snapshot.turnoverCount").value(0))
                .andExpect(jsonPath("$.data.snapshot.reversedExcludedCount").value(1))
                .andExpect(jsonPath("$.data.snapshot.activeVehicleCount").value(1))
                .andExpect(jsonPath("$.data.snapshot.turnoverRate").value(0.0));

        // 周（2026-09-14 周一 ~ 09-21）：含 09-16 的还车，分子 3、剔除 1、分母 2
        long week = generate("WEEK", "2026-09-15", null);
        mockMvc.perform(get("/api/turnover/snapshots/" + week))
                .andExpect(jsonPath("$.data.snapshot.periodStart").value("2026-09-14T00:00:00"))
                .andExpect(jsonPath("$.data.snapshot.periodEnd").value("2026-09-21T00:00:00"))
                .andExpect(jsonPath("$.data.snapshot.turnoverCount").value(3))
                .andExpect(jsonPath("$.data.snapshot.reversedExcludedCount").value(1))
                .andExpect(jsonPath("$.data.snapshot.activeVehicleCount").value(2))
                .andExpect(jsonPath("$.data.snapshot.turnoverRate").value(1.5));

        // 月（2026-09）：同周口径
        long month = generate("MONTH", "2026-09-15", null);
        mockMvc.perform(get("/api/turnover/snapshots/" + month))
                .andExpect(jsonPath("$.data.snapshot.periodStart").value("2026-09-01T00:00:00"))
                .andExpect(jsonPath("$.data.snapshot.periodEnd").value("2026-10-01T00:00:00"))
                .andExpect(jsonPath("$.data.snapshot.turnoverCount").value(3));

        // 列表过滤
        mockMvc.perform(get("/api/turnover/snapshots?periodType=DAY&branchCode=B01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void recomputeIsIdempotentAndConfirmFreezes() throws Exception {
        fixture();

        long first = generate("DAY", "2026-09-15", null);
        JsonNode firstSnapshot = objectMapper.readTree(mockMvc.perform(
                        get("/api/turnover/snapshots/" + first))
                .andReturn().getResponse().getContentAsString()).get("data").get("snapshot");
        String snapshotNo = firstSnapshot.get("snapshotNo").asText();

        // 同口径重算：快照号不变、仍只有一份、计数一致
        long second = generate("DAY", "2026-09-15", null);
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        mockMvc.perform(get("/api/turnover/snapshots?periodType=DAY&branchCode=*"))
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/turnover/snapshots/" + second))
                .andExpect(jsonPath("$.data.snapshot.snapshotNo").value(snapshotNo))
                .andExpect(jsonPath("$.data.snapshot.turnoverCount").value(2))
                .andExpect(jsonPath("$.data.orderDetailCount").value(3));

        // 确认冻结
        mockMvc.perform(write(post("/api/turnover/snapshots/" + second + "/confirm"),
                        "2026-09-16T10:00:00").content("{\"remark\":\"早会核对无误\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedBy").value("tester"));

        // 冻结后拒绝重算、拒绝重复确认
        mockMvc.perform(write(post("/api/turnover/snapshots"), "2026-09-16T11:00:00")
                        .content("{\"periodType\":\"DAY\",\"bizDate\":\"2026-09-15\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/turnover/snapshots/" + second + "/confirm"),
                        "2026-09-16T11:30:00").content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void emptyPeriodYieldsZeroRateAndBadPeriodTypeRejected() throws Exception {
        fixture();
        // 无活动的业务日：分子分母均 0，比率 0
        long empty = generate("DAY", "2026-09-20", null);
        mockMvc.perform(get("/api/turnover/snapshots/" + empty))
                .andExpect(jsonPath("$.data.snapshot.turnoverCount").value(0))
                .andExpect(jsonPath("$.data.snapshot.activeVehicleCount").value(0))
                .andExpect(jsonPath("$.data.snapshot.turnoverRate").value(0.0))
                .andExpect(jsonPath("$.data.orderDetailCount").value(0))
                .andExpect(jsonPath("$.data.vehicleDetailCount").value(0));

        // 非法周期类型 400
        mockMvc.perform(write(post("/api/turnover/snapshots"), "2026-09-16T09:00:00")
                        .content("{\"periodType\":\"YEAR\",\"bizDate\":\"2026-09-15\"}"))
                .andExpect(status().isBadRequest());
    }
}
