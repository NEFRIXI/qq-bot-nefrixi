package com.mybotweb.blog.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mybotweb.blog.entity.AppUser;
import com.mybotweb.blog.entity.AppUserStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前登录用户的公开信息 —— 给 {@code GET /client/api/user/me} 用。
 *
 * <p><b>这个 VO 的存在意义就是"不返回实体"</b>：
 * {@link AppUser} 里有 {@code passwordHash}，直接把实体序列化出去
 * 就等于把密码哈希交给前端。用这个 VO 把它挡在编译期之外 ——
 * 本类里根本没有那个字段，想漏也漏不出来。
 *
 * <p>和 {@link LoginVo} 的分工：登录接口只要 token 和名字就够，
 * "我是谁"这种完整信息走 /me。
 *
 * @author mybotweb
 */
@Data
public class UserInfoVo {

    /** 用户 id。做"这是我的帖子吗"这类判断时前端要用它 */
    private Long id;

    /** 登录名 */
    private String username;

    /** 显示名。可能为空，前端回退到 username */
    private String nickname;

    /**
     * 状态：0 正常 / 1 已封禁。
     *
     * <p>正常登录时这个值一定是 0（被封禁的登不进来），
     * 留着是为了将来"边看边被封"的场景能给出准确提示。
     */
    private Integer status;

    /** 状态的中文说明，省得前端再写一次映射 */
    private String statusText;

    /** 注册时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 从实体转换。
     *
     * @param user 数据库里的用户
     * @return 不含密码字段的 VO
     */
    public static UserInfoVo from(AppUser user) {
        UserInfoVo vo = new UserInfoVo();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setStatusText(AppUserStatus.describe(user.getStatus()));
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
