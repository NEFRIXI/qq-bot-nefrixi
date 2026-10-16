package com.mybotweb.blog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台管理员实体 —— 对应数据库的 {@code admin_user} 表。
 *
 * <p><b>它和 {@link com.mybotweb.security.UserContext} 是两回事，别搞混</b>
 * <table border="1">
 *   <tr><th></th><th>UserContext</th><th>AdminUser（本类）</th></tr>
 *   <tr>
 *     <td>谁</td>
 *     <td>在 QQ 里跟机器人说话的人</td>
 *     <td>登录网站后台的人</td>
 *   </tr>
 *   <tr>
 *     <td>身份怎么来的</td>
 *     <td>腾讯给的 openid + 机器人白名单</td>
 *     <td>网站自己的账号密码</td>
 *   </tr>
 *   <tr>
 *     <td>存在哪</td>
 *     <td>不存库，每条消息构造一次</td>
 *     <td>admin_user 表</td>
 *   </tr>
 * </table>
 * 两者的管理员权限<b>互不相通</b>：QQ 里的管理员不等于网站后台的管理员。
 * 这不是疏忽，是刻意的 —— 它们的信任来源完全不同。
 *
 * <p>{@code passwordHash} 字段会被 Jackson 序列化成 JSON 吗？
 * 会，但本类<b>从不直接返回给前端</b>：响应里给的是
 * {@code LoginVo} / 用户名和昵称，不含这个字段。
 * 靠"不返回"而不是靠 {@code @JsonIgnore}，是因为加了注解会让人误以为
 * "这个字段可以随便往响应里塞"。
 *
 * @author mybotweb
 */
@Data
@TableName("admin_user")
public class AdminUser {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，数据库上有唯一索引 */
    private String username;

    /**
     * BCrypt 哈希后的密码。
     *
     * <p>数据库里存的是哈希，不是明文 —— 就算整个库被拖走，
     * 也算不出原始密码。校验时用
     * {@code BCryptPasswordEncoder.matches(用户输入的明文, 这个哈希)}。
     *
     * <p>哈希固定 60 个字符，同一密码每次算出来的结果都不一样
     * （内部掺了随机盐），所以不能直接用字符串比较。
     */
    private String passwordHash;

    /** 显示名，出现在后台页面右上角 */
    private String nickname;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后修改时间（改密码时更新） */
    private LocalDateTime updatedAt;
}
