package com.evops.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动初始化：无任何账号时写入内置管理员 admin/admin123（首次登录请立即改密）。
 * 账号表已有数据时绝不重置，避免覆盖生产口令。
 */
@Component
@Order(0)
public class SysUserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SysUserBootstrap.class);

    private final SysUserMapper userMapper;
    private final PasswordDigester digester;

    public SysUserBootstrap(SysUserMapper userMapper, PasswordDigester digester) {
        this.userMapper = userMapper;
        this.digester = digester;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            return;
        }
        String rawPassword = "admin123";
        SysUser admin = new SysUser();
        admin.setUsername("admin");
        admin.setDisplayName("系统管理员");
        admin.setSalt(digester.newSalt());
        admin.setPasswordHash(digester.hash(rawPassword, admin.getSalt()));
        admin.setRoles(AuthService.ROLE_ADMIN + "," + AuthService.ROLE_DISPATCHER);
        admin.setStatus("ENABLED");
        admin.setTokenVersion(0);
        admin.setPasswordUpdatedTime(LocalDateTime.now());
        admin.setCreateTime(LocalDateTime.now());
        admin.setUpdateTime(LocalDateTime.now());
        userMapper.insert(admin);
        log.warn("已初始化内置管理员账号 admin/{}，请在首次登录后立即修改密码", rawPassword);
    }
}
