package com.evops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多端 JWT 登录态集成测试（auth-1）：内存 H2 全链路。
 * 覆盖：登录/错误口令、无令牌 401、令牌回库鉴权、防冒充操作人、
 * 刷新一次性轮换与重用吊销全家族、登出立即失效、改密全端下线、VIEWER 只读、ADMIN 管理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-test;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "evops.recon.enabled=false"
})
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private static String reqNo() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 解析登录/刷新响应中的双令牌 */
    private Tokens parseTokens(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode data = json.get("data");
        return new Tokens(data.get("accessToken").asText(), data.get("refreshToken").asText());
    }

    private MvcResult loginRaw(String username, String password, String device) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\""
                + (device == null ? "" : ",\"deviceType\":\"" + device + "\"") + "}";
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private Tokens login(String username, String password, String device) throws Exception {
        MvcResult result = loginRaw(username, password, device);
        org.junit.jupiter.api.Assertions.assertEquals(200, result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
        return parseTokens(result);
    }

    private MvcResult refreshRaw(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn();
    }

    private static void assertStatus(int expected, MvcResult result) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, result.getResponse().getStatus(),
                () -> {
                    try {
                        return result.getResponse().getContentAsString();
                    } catch (Exception e) {
                        return "";
                    }
                });
    }

    private String bearer(Tokens t) {
        return "Bearer " + t.access;
    }

    @Test
    void loginBootstrapAdminAndRejectBadCredentials() throws Exception {
        // 健康检查匿名
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        // 未带令牌访问受保护接口 401
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        // 错误口令 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized());

        // 内置管理员
        MvcResult result = loginRaw("admin", "admin123", "WEB");
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(json.get("data").get("roles").toString().contains("ADMIN"));

        // 令牌可查自身身份
        Tokens admin = parseTokens(result);
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));

        // 篡改签名的令牌 401
        String tampered = admin.access.substring(0, admin.access.length() - 1)
                + (admin.access.endsWith("A") ? "B" : "A");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorComesFromTokenNotHeaderSpoof() throws Exception {
        Tokens admin = login("admin", "admin123", "WEB");
        String body = "{\"plateNo\":\"京AUTH-" + reqNo().substring(0, 8) + "\",\"model\":\"M\"}";
        // 客户端自报 X-Operator=spoof，但落库操作人必须取令牌内的 admin
        mockMvc.perform(post("/api/vehicles")
                        .header("Authorization", bearer(admin))
                        .header("X-Operator", "spoof")
                        .header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operator").value("admin"));
    }

    @Test
    void refreshRotationIsOneTimeAndReuseRevokesWholeSession() throws Exception {
        Tokens admin = login("admin", "admin123", "WEB");
        createUser(admin, "disp01", "Disp@ss123", "DISPATCHER");
        Tokens t1 = login("disp01", "Disp@ss123", "WEB");

        // 首次换新成功
        MvcResult r2 = refreshRaw(t1.refresh);
        org.junit.jupiter.api.Assertions.assertEquals(200, r2.getResponse().getStatus(),
                r2.getResponse().getContentAsString());
        Tokens t2 = parseTokens(r2);
        org.junit.jupiter.api.Assertions.assertNotEquals(t1.access, t2.access);

        // 旧刷新令牌再次使用 → 401，且整个会话家族被吊销
        assertStatus(401, refreshRaw(t1.refresh));
        // 新刷新令牌也已连带吊销
        assertStatus(401, refreshRaw(t2.refresh));
        // 访问令牌（含刚换新出来的）立即失效
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t2.access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + t1.access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesTokensImmediately() throws Exception {
        Tokens t = login("admin", "admin123", "APP");
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(t)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", bearer(t)))
                .andExpect(status().isOk());

        // 登出后旧访问令牌立即失效
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(t)))
                .andExpect(status().isUnauthorized());
        // 刷新令牌同样失效，需重新登录
        assertStatus(401, refreshRaw(t.refresh));
    }

    @Test
    void passwordChangeKicksAllDevices() throws Exception {
        Tokens admin = login("admin", "admin123", "WEB");
        createUser(admin, "disp02", "OldP@ss123", "DISPATCHER");

        Tokens web = login("disp02", "OldP@ss123", "WEB");
        Tokens app = login("disp02", "OldP@ss123", "APP");

        // WEB 端改密
        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", bearer(web))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"OldP@ss123\",\"newPassword\":\"NewP@ss456\"}"))
                .andExpect(status().isOk());

        // 两端访问令牌立即失效
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(web)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(app)))
                .andExpect(status().isUnauthorized());
        // 两端刷新令牌也都被吊销
        assertStatus(401, refreshRaw(web.refresh));
        assertStatus(401, refreshRaw(app.refresh));

        // 旧口令登录失败，新口令成功
        assertStatus(401, loginRaw("disp02", "OldP@ss123", "WEB"));
        login("disp02", "NewP@ss456", "WEB");
    }

    @Test
    void viewerIsReadOnlyAndUserManagementIsAdminOnly() throws Exception {
        Tokens admin = login("admin", "admin123", "WEB");
        createUser(admin, "viewer01", "View@ss123", "VIEWER");
        createUser(admin, "disp03", "Disp@ss123", "DISPATCHER");

        Tokens viewer = login("viewer01", "View@ss123", "WEB");
        // 只读角色：GET 放行，写操作 403
        mockMvc.perform(get("/api/vehicles").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/vehicles")
                        .header("Authorization", bearer(viewer))
                        .header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNo\":\"京V-" + reqNo().substring(0, 8) + "\"}"))
                .andExpect(status().isForbidden());

        // 非管理员访问账号管理 403；管理员 200
        Tokens disp = login("disp03", "Disp@ss123", "WEB");
        mockMvc.perform(get("/api/auth/users").header("Authorization", bearer(disp)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        // 停用账号：会话被吊销且无法再登录
        JsonNode users = objectMapper.readTree(mockMvc.perform(
                        get("/api/auth/users").header("Authorization", bearer(admin)))
                .andReturn().getResponse().getContentAsString()).get("data");
        long disp03Id = -1;
        for (JsonNode u : users) {
            if ("disp03".equals(u.get("username").asText())) {
                disp03Id = u.get("id").asLong();
            }
        }
        mockMvc.perform(put("/api/auth/users/" + disp03Id + "/status")
                        .header("Authorization", bearer(admin))
                        .header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        // 已持有的访问令牌下一个请求即失效
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(disp)))
                .andExpect(status().isUnauthorized());
        // 停用账号登录 403
        assertStatus(403, loginRaw("disp03", "Disp@ss123", "WEB"));
    }

    @Test
    void multiDeviceSessionsAndSilentRefreshFlow() throws Exception {
        Tokens admin = login("admin", "admin123", "WEB");
        createUser(admin, "disp04", "Disp@ss123", "DISPATCHER");
        Tokens web = login("disp04", "Disp@ss123", "WEB");
        Tokens app = login("disp04", "Disp@ss123", "APP");

        // 两端是独立会话
        JsonNode sessions = objectMapper.readTree(mockMvc.perform(
                        get("/api/auth/sessions").header("Authorization", bearer(web)))
                .andReturn().getResponse().getContentAsString()).get("data");
        org.junit.jupiter.api.Assertions.assertEquals(2, sessions.size());

        // 无感刷新场景：访问令牌失效（此处用非法令牌模拟过期）→ 401；
        // 用刷新令牌换新 → 拿新访问令牌重放业务请求成功
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        Tokens renewed = parseTokens(refreshRaw(web.refresh));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + renewed.access))
                .andExpect(status().isOk());
        // APP 端不受 WEB 端正常轮换影响
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(app)))
                .andExpect(status().isOk());

        // 安全事件留痕：可查到登录与刷新记录
        mockMvc.perform(get("/api/auth/events?username=disp04")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.eventType=='LOGIN')]").exists())
                .andExpect(jsonPath("$.data[?(@.eventType=='REFRESH')]").exists());
    }

    private void createUser(Tokens admin, String username, String password, String role) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"roles\":[\"" + role + "\"]}";
        mockMvc.perform(post("/api/auth/users")
                        .header("Authorization", bearer(admin))
                        .header("X-Request-No", reqNo())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static final class Tokens {
        final String access;
        final String refresh;

        Tokens(String access, String refresh) {
            this.access = access;
            this.refresh = refresh;
        }
    }
}
