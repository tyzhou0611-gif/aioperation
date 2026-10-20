package com.aiops.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 登录响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginVO {

    private String token;
    private UserBasicVO user;
    private List<?> menus;
    private Set<String> permissions;
}