package com.aiops.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户基本信息 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBasicVO {

    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String avatar;
    private Integer status;
    private String createTime;
}