package com.evops.auth.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

public class UserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3-32 位之间")
    private String username;

    @Size(max = 64, message = "姓名长度不能超过 64")
    private String displayName;

    @NotBlank(message = "初始密码不能为空")
    @Size(min = 8, max = 64, message = "初始密码长度需在 8-64 位之间")
    private String password;

    /** 角色列表：ADMIN / DISPATCHER / VIEWER；空则默认 DISPATCHER */
    private List<String> roles;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
}
