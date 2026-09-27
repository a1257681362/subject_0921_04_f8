package com.evops.auth;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令与令牌摘要工具（仅 JDK 原生算法，无外部依赖）：
 * - 口令：PBKDF2WithHmacSHA256，每账号 16 字节随机盐、迭代 120000 次，输出 256 位；
 * - 令牌：SHA-256 用于库内存储刷新令牌（原值绝不落库）与等值查找。
 */
@Component
public class PasswordDigester {

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final int ITERATIONS = 120_000;

    private final SecureRandom random = new SecureRandom();

    /** 生成随机盐（Base64） */
    public String newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** 按给定盐计算口令哈希（Base64） */
    public String hash(String rawPassword, String saltBase64) {
        char[] chars = rawPassword == null ? new char[0] : rawPassword.toCharArray();
        byte[] salt = Base64.getDecoder().decode(saltBase64);
        try {
            PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(encoded);
        } catch (Exception e) {
            throw new IllegalStateException("口令哈希算法不可用", e);
        }
    }

    /** 常量时间比对口令 */
    public boolean matches(String rawPassword, String saltBase64, String expectedHash) {
        if (rawPassword == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(rawPassword, saltBase64).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 刷新令牌 SHA-256（Hex 小写，长度 64，对应库列 VARCHAR(64)） */
    public String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** URL 安全随机串（jti / 刷新令牌熵源） */
    public String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
