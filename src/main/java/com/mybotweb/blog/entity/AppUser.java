package com.mybotweb.blog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 普通用户 —— 对应 {@code app_user} 表。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它和 {@link AdminUser} 是两个东西，绝不能混用</b>
 *
 * <table border="1">
 *   <tr><th></th><th>AdminUser</th><th>AppUser（本类）</th></tr>
 *   <tr><td>谁</td><td>站点所有者</td><td>访客自己注册的账号</td></tr>
 *   <tr><td>从哪来</td><td>配置里预置 / 管理员添加</td><td>自助注册</td></tr>
 *   <tr><td>能做什么</td><td>删文章、管用户、看服务器状态</td><td>发帖、评论，只能管自己的</td></tr>
 *   <tr><td>数量</td><td>一两个</td><td>不设上限</td></tr>
 * </table>
 *
 * <p>两张表分开是刻意的（理由写在 V3 脚本里）：混成一张表 + role 字段的话，
 * 任何一处查询忘了带 role 条件，就越权了。分开之后越权在类型层面就不可能发生。
 *
 * @author mybotweb
 */
@Data
@TableName("app_user")
public class AppUser {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，有唯一索引 */
    private String username;

    /**
     * BCrypt 哈希后的密码。
     *
     * <p>和 {@link AdminUser#getPasswordHash()} 一样，存的是哈希不是明文。
     * 本类<b>从不直接序列化给前端</b> —— 响应里给的是 {@code UserInfoVo}。
     */
    private String passwordHash;

    /** 显示名。为空时前端回退到用户名 */
    private String nickname;

    /**
     * 状态，取值见 {@link AppUserStatus}：0 = 正常，1 = 已封禁。
     *
     * <p>封禁是<b>可逆</b>的：管理员把 status 改回 0 就恢复了。
     * 所以没有做成"删除"，也没有删除时间字段。
     */
    private Integer status;

    /** 注册时间 */
    private LocalDateTime createdAt;

    /** 最后修改时间 */
    private LocalDateTime updatedAt;
}
