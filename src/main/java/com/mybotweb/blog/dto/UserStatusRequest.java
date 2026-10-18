package com.mybotweb.blog.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员修改用户状态的请求 —— {@code PATCH /admin/api/users/{id}/status}。
 *
 * <p>形如：
 * <pre>{@code { "status": 1 }}</pre>
 *
 * <p>0 = 正常，1 = 封禁。用 PATCH 而不是 PUT：
 * 这个请求只改一个字段，语义上就是"局部更新"，
 * PUT 的约定是"整体替换"，会让读代码的人以为要传完整对象。
 *
 * @author mybotweb
 */
@Data
public class UserStatusRequest {

    /** 目标状态：0 正常 / 1 封禁。取值合法性在 Service 里再校验一次 */
    @NotNull(message = "请选择目标状态")
    private Integer status;
}
