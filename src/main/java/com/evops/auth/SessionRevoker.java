package com.evops.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 会话吊销（独立事务）。刷新令牌重用/并发重用时，必须先提交吊销再向客户端抛 401，
 * 否则吊销会随外层事务回滚而落空（盗用者即可继续用新令牌）。
 */
@Component
public class SessionRevoker {

    private final UserSessionMapper sessionMapper;
    private final RefreshTokenMapper refreshMapper;
    private final AuthEventMapper eventMapper;

    public SessionRevoker(UserSessionMapper sessionMapper, RefreshTokenMapper refreshMapper,
                          AuthEventMapper eventMapper) {
        this.sessionMapper = sessionMapper;
        this.refreshMapper = refreshMapper;
        this.eventMapper = eventMapper;
    }

    /** 立即吊销会话及其全部刷新令牌（独立事务提交，不受调用方回滚影响） */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void revokeNow(long sessionId, String reason, String username, String clientIp) {
        LocalDateTime now = LocalDateTime.now();
        UserSession session = sessionMapper.selectById(sessionId);
        sessionMapper.update(null, new LambdaUpdateWrapper<UserSession>()
                .eq(UserSession::getId, sessionId)
                .set(UserSession::getStatus, AuthService.SESSION_REVOKED)
                .set(UserSession::getRevokeReason, reason == null ? null
                        : reason.substring(0, Math.min(reason.length(), 200)))
                .set(UserSession::getUpdateTime, now));

        refreshMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getSessionId, sessionId)
                .ne(RefreshToken::getStatus, AuthService.RT_REVOKED)
                .set(RefreshToken::getStatus, AuthService.RT_REVOKED)
                .set(RefreshToken::getUpdateTime, now));

        AuthEvent event = new AuthEvent();
        event.setUserId(session == null ? null : session.getUserId());
        event.setUsername(username == null ? "unknown" : username);
        event.setEventType(AuthEvent.SESSION_REVOKED);
        event.setSessionId(sessionId);
        event.setDeviceType(session == null ? null : session.getDeviceType());
        event.setDetail(reason);
        event.setClientIp(clientIp);
        event.setEventTime(now);
        event.setOperator(event.getUsername());
        event.setCreateTime(now);
        eventMapper.insert(event);
    }
}
