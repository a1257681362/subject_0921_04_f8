package com.evops.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT(HS256) 签发与校验，仅用 JDK 原生 HmacSHA256 + Jackson，无第三方依赖。
 *
 * 访问/刷新令牌同构，以 typ 区分（ACCESS / REFRESH），声明：
 * sub=登录名, uid=用户id, roles=角色逗号串, tv=令牌版本(改密自增),
 * sid=会话id, jti=令牌唯一标识, iat/exp=签发/过期秒级时间戳。
 *
 * 校验只负责签名与时间窗；会话存活、账号状态、版本匹配由 AuthService 回库判定，
 * 因而登出、改密踢下线、停用账号都能立即生效（无状态 JWT + 有状态回查）。
 */
@Component
public class JwtService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HMAC = "HmacSHA256";

    private final AuthProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtService(AuthProperties properties) {
        this.properties = properties;
    }

    public static final String TYP_ACCESS = "ACCESS";
    public static final String TYP_REFRESH = "REFRESH";

    public String issueAccess(long userId, String username, String roles, int tokenVersion,
                              long sessionId, String jti, Instant now) {
        Map<String, Object> claims = baseClaims(userId, username, roles, tokenVersion, sessionId, jti, now);
        claims.put("typ", TYP_ACCESS);
        claims.put("exp", now.plusSeconds(properties.effectiveAccessTtlSeconds()).getEpochSecond());
        return sign(claims);
    }

    public String issueRefresh(long userId, String username, String roles, int tokenVersion,
                               long sessionId, String jti, Instant now) {
        Map<String, Object> claims = baseClaims(userId, username, roles, tokenVersion, sessionId, jti, now);
        claims.put("typ", TYP_REFRESH);
        claims.put("exp", now.plusSeconds(properties.getRefreshTtlDays() * 86400).getEpochSecond());
        return sign(claims);
    }

    private Map<String, Object> baseClaims(long userId, String username, String roles, int tokenVersion,
                                           long sessionId, String jti, Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", username);
        claims.put("uid", userId);
        claims.put("roles", roles);
        claims.put("tv", tokenVersion);
        claims.put("sid", sessionId);
        claims.put("jti", jti);
        claims.put("iat", now.getEpochSecond());
        return claims;
    }

    /**
     * 校验并解析令牌。签名不符、结构非法、过期或非期望类型均抛 IllegalArgumentException。
     */
    public Claims parse(String token, String expectedType, Instant now) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("令牌为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("令牌结构非法");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = hmac(signingInput);
        byte[] actual;
        try {
            actual = B64D.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("令牌签名编码非法");
        }
        if (!constantTimeEquals(expected, actual)) {
            throw new IllegalArgumentException("令牌签名校验失败");
        }

        Map<String, Object> map;
        try {
            byte[] json = B64D.decode(parts[1]);
            map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("令牌内容无法解析");
        }

        Object typ = map.get("typ");
        if (!expectedType.equals(typ)) {
            throw new IllegalArgumentException("令牌类型不匹配");
        }
        long exp = asLong(map.get("exp"));
        if (now.getEpochSecond() >= exp) {
            throw new IllegalArgumentException("令牌已过期");
        }
        long iat = asLong(map.get("iat"));
        if (iat > now.getEpochSecond() + 60) {
            throw new IllegalArgumentException("令牌签发时间异常");
        }
        return new Claims(
                String.valueOf(map.get("sub")),
                asLong(map.get("uid")),
                map.get("roles") == null ? "" : String.valueOf(map.get("roles")),
                (int) asLong(map.get("tv")),
                asLong(map.get("sid")),
                String.valueOf(map.get("jti")),
                iat,
                exp);
    }

    private String sign(Map<String, Object> claims) {
        try {
            String header = B64.encodeToString(
                    "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = B64.encodeToString(objectMapper.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            String signature = B64.encodeToString(hmac(signingInput));
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("令牌签发失败", e);
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(
                    properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 不可用", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /** 已校验签名与时间窗的令牌声明（会话/账号状态仍需调用方回库确认） */
    public static class Claims {
        public final String username;
        public final long userId;
        public final String roles;
        public final int tokenVersion;
        public final long sessionId;
        public final String jti;
        public final long issuedAt;
        public final long expiresAt;

        public Claims(String username, long userId, String roles, int tokenVersion, long sessionId,
                      String jti, long issuedAt, long expiresAt) {
            this.username = username;
            this.userId = userId;
            this.roles = roles;
            this.tokenVersion = tokenVersion;
            this.sessionId = sessionId;
            this.jti = jti;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }
    }
}
