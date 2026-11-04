package com.aiops.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 用户信息响应（/api/auth/info）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserInfoVO {

    private UserBasicVO user;
    private Set<String> roles;
    private Set<String> permissions;
    private List<?> menus;
}