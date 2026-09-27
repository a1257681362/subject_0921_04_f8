package com.evops.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.auth.dto.ChangePasswordRequest;
import com.evops.auth.dto.LoginRequest;
import com.evops.auth.dto.UserCreateRequest;
import com.evops.common.BizException;
import com.evops.common.ForbiddenException;
import com.evops.common.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录态与账号服务（auth-1）。
 *
 * 安全要点：
 * - 访问令牌短期、签名即合法，但每次请求都回库校验会话状态/账号状态/令牌版本，
 *   因此登出、改密、停用都能在下一个请求立即生效（不靠等待令牌自然过期）；
 * - 刷新令牌只存 SHA-256；换新走条件更新（仅 ACTIVE 可换），天然一次性；
 *   并发两次换新只有一方成功；已轮换/已吊销的令牌再现即判定盗用，吊销该端整条家族链；
 * - 改密/管理员重置/停用账号：token_version+1 并吊销该账号全部会话，所有端强制下线。
 */
@Service
public class AuthService {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DISPATCHER = "DISPATCHER";
    public static final String ROLE_VIEWER = "VIEWER";
    public static final Set<String> ALL_ROLES = new LinkedHashSet<>(Arrays.asList(
            ROLE_ADMIN, ROLE_DISPATCHER, ROLE_VIEWER));

    public static final String SESSION_ACTIVE = "ACTIVE";
    public static final String SESSION_LOGGED_OUT = "LOGGED_OUT";
    public static final String SESSION_REVOKED = "REVOKED";

    public static final String RT_ACTIVE = "ACTIVE";
    public static final String RT_ROTATED = "ROTATED";
    public static final String RT_REVOKED = "REVOKED";

    private final SysUserMapper userMapper;
    private final UserSessionMapper sessionMapper;
    private final RefreshTokenMapper refreshMapper;
    private final AuthEventMapper eventMapper;
    private final PasswordDigester digester;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final SessionRevoker sessionRevoker;

    public AuthService(SysUserMapper userMapper, UserSessionMapper sessionMapper,
                       RefreshTokenMapper refreshMapper, AuthEventMapper eventMapper,
                       PasswordDigester digester, JwtService jwtService, AuthProperties properties,
                       SessionRevoker sessionRevoker) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.refreshMapper = refreshMapper;
        this.eventMapper = eventMapper;
        this.digester = digester;
        this.jwtService = jwtService;
        this.properties = properties;
        this.sessionRevoker = sessionRevoker;
    }

    // ============================ 登录 ============================

    @Transactional(rollbackFor = Exception.class)
    public TokenResponse login(LoginRequest request, String clientIp, String userAgent) {
        String username = normalizeUsername(request.getUsername());
        String deviceType = normalizeDevice(request.getDeviceType());
        SysUser user = findByUsername(username);

        if (user == null || !digester.matches(request.getPassword(), user.getSalt(), user.getPasswordHash())) {
            recordEvent(user == null ? null : user.getId(), username, AuthEvent.LOGIN_FAILED, null,
                    deviceType, "用户名或密码错误", clientIp, username);
            throw new UnauthorizedException("用户名或密码错误");
        }
        if (!"ENABLED".equals(user.getStatus())) {
            recordEvent(user.getId(), username, AuthEvent.LOGIN_FAILED, null,
                    deviceType, "账号已停用", clientIp, username);
            throw new ForbiddenException("账号已停用，请联系管理员");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime refreshExpire = now.plusDays(properties.getRefreshTtlDays());

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setDeviceType(deviceType);
        session.setStatus(SESSION_ACTIVE);
        session.setLoginAt(now);
        session.setLastRefreshAt(now);
        session.setRefreshExpireAt(refreshExpire);
        session.setClientIp(truncate(clientIp, 64));
        session.setUserAgent(truncate(userAgent, 300));
        session.setCreateTime(now);
        session.setUpdateTime(now);
        sessionMapper.insert(session);

        IssuedTokens tokens = issueTokens(user, session.getId(), now, refreshExpire);

        session.setAccessJti(tokens.accessJti);
        session.setRefreshJti(tokens.refreshJti);
        session.setRefreshTokenHash(digester.sha256Hex(tokens.refreshTokenRaw));
        session.setUpdateTime(now);
        sessionMapper.updateById(session);

        insertRefreshLedger(user.getId(), session.getId(), tokens, now, refreshExpire);

        user.setLastLoginAt(now);
        user.setUpdateTime(now);
        userMapper.updateById(user);

        recordEvent(user.getId(), username, AuthEvent.LOGIN, session.getId(), deviceType,
                "登录成功", clientIp, username);

        return tokens.toResponse(user, deviceType);
    }

    // ============================ 刷新（一次性轮换） ============================

    @Transactional(rollbackFor = Exception.class)
    public TokenResponse refresh(String rawRefreshToken, String clientIp) {
        JwtService.Claims claims;
        try {
            claims = jwtService.parse(rawRefreshToken, JwtService.TYP_REFRESH, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("刷新令牌无效或已过期");
        }

        String tokenHash = digester.sha256Hex(rawRefreshToken);
        RefreshToken ledger = refreshMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, tokenHash));
        if (ledger == null) {
            throw new UnauthorizedException("刷新令牌无效");
        }

        if (RT_REVOKED.equals(ledger.getStatus())) {
            throw new UnauthorizedException("刷新令牌已吊销，请重新登录");
        }
        if (RT_ROTATED.equals(ledger.getStatus())) {
            // 已轮换的令牌再次出现 = 被盗用：吊销该端整条家族链，防止扩散。
            // 吊销走独立事务先提交，随后抛出的 401 不会把它回滚掉。
            sessionRevoker.revokeNow(ledger.getSessionId(),
                    "检测到刷新令牌重用，疑似泄露", claims.username, clientIp);
            throw new UnauthorizedException("刷新令牌已被使用，会话已被吊销，请重新登录");
        }

        UserSession session = sessionMapper.selectById(ledger.getSessionId());
        SysUser user = userMapper.selectById(ledger.getUserId());
        if (session == null || user == null) {
            throw new UnauthorizedException("登录态不存在，请重新登录");
        }
        if (!SESSION_ACTIVE.equals(session.getStatus())) {
            throw new UnauthorizedException("会话已结束，请重新登录");
        }
        if (!"ENABLED".equals(user.getStatus())) {
            throw new ForbiddenException("账号已停用");
        }
        if (claims.tokenVersion != user.getTokenVersion()) {
            sessionRevoker.revokeNow(session.getId(), "令牌版本失配（口令已变更）",
                    user.getUsername(), clientIp);
            throw new UnauthorizedException("登录态已失效，请重新登录");
        }
        if (session.getRefreshExpireAt() != null && session.getRefreshExpireAt().isBefore(LocalDateTime.now())) {
            throw new UnauthorizedException("刷新令牌已过期，请重新登录");
        }

        // 条件更新保证一次性：并发/重放只有一方能把 ACTIVE 改成 ROTATED
        String newRefreshJti = digester.randomToken();
        int rotated = refreshMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getId, ledger.getId())
                .eq(RefreshToken::getStatus, RT_ACTIVE)
                .set(RefreshToken::getStatus, RT_ROTATED)
                .set(RefreshToken::getRotatedJti, newRefreshJti)
                .set(RefreshToken::getUpdateTime, LocalDateTime.now()));
        if (rotated != 1) {
            // 另一方已抢先轮换：同一旧令牌被第二次使用，按重用处理，吊销整条链（独立事务先提交）
            sessionRevoker.revokeNow(session.getId(), "检测到刷新令牌并发重用",
                    user.getUsername(), clientIp);
            throw new UnauthorizedException("刷新令牌已被使用，会话已被吊销，请重新登录");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime refreshExpire = now.plusDays(properties.getRefreshTtlDays());
        IssuedTokens tokens = issueTokens(user, session.getId(), now, refreshExpire, newRefreshJti);

        insertRefreshLedger(user.getId(), session.getId(), tokens, now, refreshExpire);

        session.setAccessJti(tokens.accessJti);
        session.setRefreshJti(tokens.refreshJti);
        session.setRefreshTokenHash(tokenHashOf(tokens));
        session.setLastRefreshAt(now);
        session.setRefreshExpireAt(refreshExpire);
        session.setUpdateTime(now);
        sessionMapper.updateById(session);

        recordEvent(user.getId(), user.getUsername(), AuthEvent.REFRESH, session.getId(),
                session.getDeviceType(), "刷新令牌轮换，旧 jti=" + ledger.getJti(), clientIp,
                user.getUsername());

        return tokens.toResponse(user, session.getDeviceType());
    }

    // ============================ 登出 ============================

    @Transactional(rollbackFor = Exception.class)
    public void logout(AuthIdentity identity, String clientIp) {
        UserSession session = sessionMapper.selectById(identity.getSessionId());
        if (session == null) {
            return;
        }
        revokeSessionInternal(session.getId(), SESSION_LOGGED_OUT, "主动登出",
                identity.getUsername(), clientIp, false);
        recordEvent(identity.getUserId(), identity.getUsername(), AuthEvent.LOGOUT,
                session.getId(), session.getDeviceType(), "主动登出", clientIp, identity.getUsername());
    }

    /**
     * 访问令牌回库鉴权：签名/时间窗由 JwtService 保证，此处确认
     * 会话存活、账号启用、令牌版本一致（角色实时取账号当前值，改角色立即生效）。
     */
    public AuthIdentity authenticate(String accessToken) {
        JwtService.Claims claims;
        try {
            claims = jwtService.parse(accessToken, JwtService.TYP_ACCESS, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("访问令牌无效或已过期，请使用刷新令牌换新");
        }
        UserSession session = sessionMapper.selectById(claims.sessionId);
        if (session == null || !SESSION_ACTIVE.equals(session.getStatus())) {
            throw new UnauthorizedException("登录态已失效，请重新登录");
        }
        SysUser user = userMapper.selectById(claims.userId);
        if (user == null || !"ENABLED".equals(user.getStatus())) {
            throw new UnauthorizedException("账号不存在或已停用");
        }
        if (claims.tokenVersion != user.getTokenVersion()) {
            throw new UnauthorizedException("口令已变更，请重新登录");
        }
        return new AuthIdentity(user.getId(), user.getUsername(), user.getDisplayName(),
                user.roleList(), session.getId(), claims.jti);
    }

    // ============================ 改密 / 重置 ============================

    @Transactional(rollbackFor = Exception.class)
    public void changePassword(AuthIdentity identity, ChangePasswordRequest request, String clientIp) {
        SysUser user = userMapper.selectById(identity.getUserId());
        if (user == null) {
            throw new UnauthorizedException("账号不存在");
        }
        if (!digester.matches(request.getOldPassword(), user.getSalt(), user.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new BizException("新密码不能与原密码相同");
        }
        applyNewPassword(user, request.getNewPassword());
        recordEvent(user.getId(), user.getUsername(), AuthEvent.PASSWORD_CHANGED, null, null,
                "自主修改密码，全部端已强制下线", clientIp, user.getUsername());
        // 要求“修改密码后全部端强制下线”：当前端也不例外，改完需重新登录
        revokeAllSessions(user, "密码已修改，全部端强制下线", user.getUsername(), clientIp);
    }

    @Transactional(rollbackFor = Exception.class)
    public void adminResetPassword(long userId, String newPassword, AuthIdentity operator, String clientIp) {
        requireAdmin(operator);
        SysUser user = loadUser(userId);
        applyNewPassword(user, newPassword);
        recordEvent(user.getId(), user.getUsername(), AuthEvent.PASSWORD_CHANGED, null, null,
                "管理员重置密码，全部端已强制下线", clientIp, operator.getUsername());
        revokeAllSessions(user, "管理员重置密码，全部端强制下线", operator.getUsername(), clientIp);
    }

    // ============================ 账号管理（管理员） ============================

    @Transactional(rollbackFor = Exception.class)
    public SysUser createUser(UserCreateRequest request, AuthIdentity operator, String clientIp) {
        requireAdmin(operator);
        String username = normalizeUsername(request.getUsername());
        if (findByUsername(username) != null) {
            throw new BizException("用户名已存在：" + username);
        }
        String roles = normalizeRoles(request.getRoles());
        LocalDateTime now = LocalDateTime.now();

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setDisplayName(request.getDisplayName());
        user.setSalt(digester.newSalt());
        user.setPasswordHash(digester.hash(request.getPassword(), user.getSalt()));
        user.setRoles(roles);
        user.setStatus("ENABLED");
        user.setTokenVersion(0);
        user.setPasswordUpdatedTime(now);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        userMapper.insert(user);

        recordEvent(user.getId(), username, AuthEvent.USER_CREATE, null, null,
                "创建账号，角色=" + roles, clientIp, operator.getUsername());
        user.setPasswordHash(null);
        user.setSalt(null);
        return user;
    }

    public List<SysUser> listUsers(AuthIdentity operator) {
        requireAdmin(operator);
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .orderByAsc(SysUser::getId));
        users.forEach(u -> { u.setPasswordHash(null); u.setSalt(null); });
        return users;
    }

    @Transactional(rollbackFor = Exception.class)
    public void setUserStatus(long userId, String status, AuthIdentity operator, String clientIp) {
        requireAdmin(operator);
        String normalized;
        if ("ENABLED".equalsIgnoreCase(status)) {
            normalized = "ENABLED";
        } else if ("DISABLED".equalsIgnoreCase(status)) {
            normalized = "DISABLED";
        } else {
            throw new BizException("账号状态只允许 ENABLED / DISABLED");
        }
        if (userId == operator.getUserId() && "DISABLED".equals(normalized)) {
            throw new BizException("不能停用当前登录账号");
        }
        SysUser user = loadUser(userId);
        if (normalized.equals(user.getStatus())) {
            throw new BizException("账号已是该状态");
        }
        user.setStatus(normalized);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        recordEvent(user.getId(), user.getUsername(), AuthEvent.USER_STATUS, null, null,
                "管理员" + ("ENABLED".equals(normalized) ? "启用" : "停用") + "账号",
                clientIp, operator.getUsername());
        if ("DISABLED".equals(normalized)) {
            revokeAllSessions(user, "账号被停用，全部端强制下线", operator.getUsername(), clientIp);
        }
    }

    /** 管理员强制某端下线 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeSession(long sessionId, AuthIdentity operator, String clientIp) {
        requireAdmin(operator);
        UserSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在：" + sessionId);
        }
        SysUser user = userMapper.selectById(session.getUserId());
        revokeSessionInternal(sessionId, SESSION_REVOKED, "管理员强制下线",
                operator.getUsername(), clientIp, false);
        recordEvent(session.getUserId(), user == null ? String.valueOf(session.getUserId()) : user.getUsername(),
                AuthEvent.SESSION_REVOKED, sessionId, session.getDeviceType(),
                "管理员强制下线", clientIp, operator.getUsername());
    }

    public List<UserSession> listSessions(String username, AuthIdentity operator) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<UserSession>()
                .orderByDesc(UserSession::getId);
        if (username != null && !username.trim().isEmpty()) {
            SysUser target = findByUsername(username.trim());
            if (target == null) {
                return new ArrayList<>();
            }
            wrapper.eq(UserSession::getUserId, target.getId());
        } else if (!operator.hasRole(ROLE_ADMIN)) {
            // 非管理员只许看自己的会话
            wrapper.eq(UserSession::getUserId, operator.getUserId());
        }
        List<UserSession> sessions = sessionMapper.selectList(wrapper);
        sessions.forEach(s -> s.setRefreshTokenHash(null));
        return sessions;
    }

    public List<AuthEvent> listEvents(String username, AuthIdentity operator) {
        requireAdmin(operator);
        LambdaQueryWrapper<AuthEvent> wrapper = new LambdaQueryWrapper<AuthEvent>()
                .orderByDesc(AuthEvent::getId);
        if (username != null && !username.trim().isEmpty()) {
            wrapper.eq(AuthEvent::getUsername, username.trim());
        }
        return eventMapper.selectList(wrapper);
    }

    // ============================ 内部方法 ============================

    private void applyNewPassword(SysUser user, String newPassword) {
        user.setSalt(digester.newSalt());
        user.setPasswordHash(digester.hash(newPassword, user.getSalt()));
        user.setTokenVersion(user.getTokenVersion() == null ? 1 : user.getTokenVersion() + 1);
        user.setPasswordUpdatedTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
    }

    private void revokeAllSessions(SysUser user, String reason, String operator, String clientIp) {
        List<UserSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getUserId, user.getId())
                .eq(UserSession::getStatus, SESSION_ACTIVE));
        for (UserSession session : sessions) {
            revokeSessionInternal(session.getId(), SESSION_REVOKED, reason, operator, clientIp, true);
        }
    }

    /**
     * 吊销单个会话：会话置终态、其全部 ACTIVE/ROTATED 刷新令牌置 REVOKED。
     * 访问令牌因每次请求回查会话状态，下一个请求立即失效。
     */
    private void revokeSessionInternal(long sessionId, String sessionStatus, String reason,
                                       String operator, String clientIp, boolean recordRevokedEvent) {
        LocalDateTime now = LocalDateTime.now();
        UserSession session = sessionMapper.selectById(sessionId);
        int sessionUpdated = sessionMapper.update(null, new LambdaUpdateWrapper<UserSession>()
                .eq(UserSession::getId, sessionId)
                .ne(UserSession::getStatus, sessionStatus)
                .set(UserSession::getStatus, sessionStatus)
                .set(UserSession::getRevokeReason, truncate(reason, 200))
                .set(UserSession::getUpdateTime, now));

        refreshMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getSessionId, sessionId)
                .ne(RefreshToken::getStatus, RT_REVOKED)
                .set(RefreshToken::getStatus, RT_REVOKED)
                .set(RefreshToken::getUpdateTime, now));

        if (recordRevokedEvent && (sessionUpdated == 1 || session != null)) {
            Long userId = session != null ? session.getUserId() : null;
            String deviceType = session != null ? session.getDeviceType() : null;
            String eventUser = operator != null ? operator : String.valueOf(userId);
            recordEvent(userId, eventUser, AuthEvent.SESSION_REVOKED, sessionId, deviceType,
                    reason, clientIp, eventUser);
        }
    }

    private IssuedTokens issueTokens(SysUser user, long sessionId, LocalDateTime now,
                                     LocalDateTime refreshExpire) {
        return issueTokens(user, sessionId, now, refreshExpire, digester.randomToken());
    }

    private IssuedTokens issueTokens(SysUser user, long sessionId, LocalDateTime now,
                                     LocalDateTime refreshExpire, String refreshJti) {
        Instant instant = now.atZone(java.time.ZoneId.systemDefault()).toInstant();
        String accessJti = digester.randomToken();
        String accessToken = jwtService.issueAccess(user.getId(), user.getUsername(), user.getRoles(),
                user.getTokenVersion(), sessionId, accessJti, instant);
        String refreshToken = jwtService.issueRefresh(user.getId(), user.getUsername(), user.getRoles(),
                user.getTokenVersion(), sessionId, refreshJti, instant);
        return new IssuedTokens(accessJti, accessToken, refreshJti, refreshToken,
                instant.getEpochSecond() + properties.effectiveAccessTtlSeconds(),
                refreshExpire.atZone(java.time.ZoneId.systemDefault()).toEpochSecond());
    }

    private void insertRefreshLedger(long userId, long sessionId, IssuedTokens tokens,
                                     LocalDateTime now, LocalDateTime refreshExpire) {
        RefreshToken ledger = new RefreshToken();
        ledger.setJti(tokens.refreshJti);
        ledger.setSessionId(sessionId);
        ledger.setUserId(userId);
        ledger.setTokenHash(tokenHashOf(tokens));
        ledger.setStatus(RT_ACTIVE);
        ledger.setIssuedAt(now);
        ledger.setExpireAt(refreshExpire);
        ledger.setCreateTime(now);
        ledger.setUpdateTime(now);
        refreshMapper.insert(ledger);
    }

    private String tokenHashOf(IssuedTokens tokens) {
        return digester.sha256Hex(tokens.refreshTokenRaw);
    }

    private void recordEvent(Long userId, String username, String eventType, Long sessionId,
                             String deviceType, String detail, String clientIp, String operator) {
        AuthEvent event = new AuthEvent();
        event.setUserId(userId);
        event.setUsername(username == null ? "unknown" : username);
        event.setEventType(eventType);
        event.setSessionId(sessionId);
        event.setDeviceType(deviceType);
        event.setDetail(truncate(detail, 500));
        event.setClientIp(truncate(clientIp, 64));
        event.setEventTime(LocalDateTime.now());
        event.setOperator(operator == null ? event.getUsername() : operator);
        event.setCreateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private SysUser loadUser(long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("账号不存在：" + userId);
        }
        return user;
    }

    private SysUser findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
    }

    private void requireAdmin(AuthIdentity identity) {
        if (identity == null || !identity.hasRole(ROLE_ADMIN)) {
            throw new ForbiddenException("需要管理员权限");
        }
    }

    private String normalizeRoles(List<String> input) {
        List<String> roles = input == null ? new ArrayList<>() : input;
        Set<String> normalized = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null) {
                continue;
            }
            String r = role.trim().toUpperCase();
            if (!ALL_ROLES.contains(r)) {
                throw new BizException("非法角色：" + role + "，允许值 " + ALL_ROLES);
            }
            normalized.add(r);
        }
        if (normalized.isEmpty()) {
            normalized.add(ROLE_DISPATCHER);
        }
        return normalized.stream().collect(Collectors.joining(","));
    }

    private String normalizeUsername(String username) {
        if (username == null || !username.trim().matches("[A-Za-z0-9_.-]{3,32}")) {
            throw new BizException("用户名需为 3-32 位字母数字及 _ . -");
        }
        return username.trim();
    }

    private String normalizeDevice(String deviceType) {
        if (deviceType == null) {
            return "UNKNOWN";
        }
        String d = deviceType.trim().toUpperCase();
        return "WEB".equals(d) || "APP".equals(d) ? d : "UNKNOWN";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class IssuedTokens {
        final String accessJti;
        final String accessTokenRaw;
        final String refreshJti;
        final String refreshTokenRaw;
        final long accessExpiresAt;
        final long refreshExpiresAt;

        IssuedTokens(String accessJti, String accessTokenRaw, String refreshJti, String refreshTokenRaw,
                     long accessExpiresAt, long refreshExpiresAt) {
            this.accessJti = accessJti;
            this.accessTokenRaw = accessTokenRaw;
            this.refreshJti = refreshJti;
            this.refreshTokenRaw = refreshTokenRaw;
            this.accessExpiresAt = accessExpiresAt;
            this.refreshExpiresAt = refreshExpiresAt;
        }

        TokenResponse toResponse(SysUser user, String deviceType) {
            TokenResponse response = new TokenResponse();
            response.setAccessToken(accessTokenRaw);
            response.setRefreshToken(refreshTokenRaw);
            response.setAccessExpiresAt(accessExpiresAt);
            response.setRefreshExpiresAt(refreshExpiresAt);
            response.setUserId(user.getId());
            response.setUsername(user.getUsername());
            response.setDisplayName(user.getDisplayName());
            response.setRoles(user.roleList());
            response.setDeviceType(deviceType);
            return response;
        }
    }
}
