package com.evops.auth;

import com.evops.auth.dto.ChangePasswordRequest;
import com.evops.auth.dto.LoginRequest;
import com.evops.auth.dto.RefreshRequest;
import com.evops.auth.dto.ResetPasswordRequest;
import com.evops.auth.dto.UserCreateRequest;
import com.evops.auth.dto.UserStatusRequest;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录态与账号接口（auth-1）。
 *
 * 登录/刷新匿名可用；其余接口须携带访问令牌。访问令牌过期时客户端以刷新令牌
 * 调 /refresh 静默换新（刷新令牌一次性轮换），再重放原请求，实现无感续期。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录：用户名 + 密码 + 端类型，返回访问/刷新双令牌 */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest http) {
        return ApiResponse.ok(authService.login(request, clientIp(http), http.getHeader("User-Agent")));
    }

    /**
     * 刷新：旧刷新令牌一次性换新；旧令牌再次使用即判定盗用，吊销该端全部令牌。
     * 访问令牌过期时前端静默调用此接口，拿新访问令牌重放请求（无感刷新）。
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                              HttpServletRequest http) {
        return ApiResponse.ok(authService.refresh(request.getRefreshToken(), clientIp(http)));
    }

    /** 登出：当前端会话立即失效，其访问令牌与刷新令牌即刻不可用 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest http) {
        authService.logout(AuthContext.require(), clientIp(http));
        return ApiResponse.ok(null);
    }

    /** 当前登录身份（客户端启动/令牌恢复时自检） */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        AuthIdentity identity = AuthContext.require();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", identity.getUserId());
        data.put("username", identity.getUsername());
        data.put("displayName", identity.getDisplayName());
        data.put("roles", identity.getRoles());
        data.put("sessionId", identity.getSessionId());
        return ApiResponse.ok(data);
    }

    /** 修改自己的密码：成功后该账号全部端（含当前端）强制下线，需重新登录 */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletRequest http) {
        authService.changePassword(AuthContext.require(), request, clientIp(http));
        return ApiResponse.ok(null);
    }

    // ============================ 账号管理（ADMIN） ============================

    /** 创建账号（默认 DISPATCHER 角色） */
    @PostMapping("/users")
    public ApiResponse<SysUser> createUser(@Valid @RequestBody UserCreateRequest request,
                                           HttpServletRequest http) {
        return ApiResponse.ok(authService.createUser(request, AuthContext.require(), clientIp(http)));
    }

    /** 账号列表（不返回口令哈希与盐） */
    @GetMapping("/users")
    public ApiResponse<List<SysUser>> listUsers() {
        return ApiResponse.ok(authService.listUsers(AuthContext.require()));
    }

    /** 启用/停用账号；停用即吊销其全部会话 */
    @PutMapping("/users/{id}/status")
    public ApiResponse<Void> setUserStatus(@PathVariable Long id,
                                           @Valid @RequestBody UserStatusRequest request,
                                           HttpServletRequest http) {
        authService.setUserStatus(id, request.getStatus(), AuthContext.require(), clientIp(http));
        return ApiResponse.ok(null);
    }

    /** 管理员重置他人密码：成功后该账号全部端强制下线 */
    @PostMapping("/users/{id}/password/reset")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody ResetPasswordRequest request,
                                           HttpServletRequest http) {
        authService.adminResetPassword(id, request.getNewPassword(), AuthContext.require(), clientIp(http));
        return ApiResponse.ok(null);
    }

    /** 在线会话查询：管理员可按用户名查全部；其他角色只返回自己的 */
    @GetMapping("/sessions")
    public ApiResponse<List<UserSession>> sessions(@RequestParam(required = false) String username) {
        return ApiResponse.ok(authService.listSessions(username, AuthContext.require()));
    }

    /** 管理员强制某端下线 */
    @PostMapping("/sessions/{id}/revoke")
    public ApiResponse<Void> revokeSession(@PathVariable Long id, HttpServletRequest http) {
        authService.revokeSession(id, AuthContext.require(), clientIp(http));
        return ApiResponse.ok(null);
    }

    /** 登录态安全事件流水（ADMIN）：登录/刷新/登出/改密/吊销/重用全部可追溯 */
    @GetMapping("/events")
    public ApiResponse<List<AuthEvent>> events(@RequestParam(required = false) String username) {
        return ApiResponse.ok(authService.listEvents(username, AuthContext.require()));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
