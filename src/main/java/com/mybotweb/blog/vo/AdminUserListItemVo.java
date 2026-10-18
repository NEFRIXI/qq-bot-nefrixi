package com.mybotweb.blog.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mybotweb.blog.entity.AppUser;
import com.mybotweb.blog.entity.AppUserStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台"用户管理"列表里的一行。
 *
 * <p>和 {@link UserInfoVo} 几乎一样，但这里是<b>管理员看别人</b>的场景，
 * 所以多给了 {@code statusText}，方便列表直接渲染"正常 / 已封禁"标签。
 *
 * <p><b>同样没有 passwordHash</b>：本类根本没有那个字段。
 * 后台列表是最容易被截图、被分享出去的地方，密码哈希不该出现在这里。
 *
 * @author mybotweb
 */
@Data
public class AdminUserListItemVo {

    /** 用户 id */
    private Long id;

    /** 登录名 */
    private String username;

    /** 显示名 */
    private String nickname;

    /** 状态：0 正常 / 1 已封禁 */
    private Integer status;

    /** 状态中文 */
    private String statusText;

    /** 注册时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 从实体转换。
     *
     * @param user 用户
     * @return 列表项
     */
    public static AdminUserListItemVo from(AppUser user) {
        AdminUserListItemVo vo = new AdminUserListItemVo();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setStatusText(AppUserStatus.describe(user.getStatus()));
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
