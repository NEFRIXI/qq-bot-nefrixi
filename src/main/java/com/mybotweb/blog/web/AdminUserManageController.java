package com.mybotweb.blog.web;

import com.mybotweb.blog.dto.UserStatusRequest;
import com.mybotweb.blog.service.AdminUserManageService;
import com.mybotweb.blog.vo.AdminUserListItemVo;
import com.mybotweb.blog.vo.PageVo;
import com.mybotweb.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的"用户管理"接口。
 *
 * <p>挂在 {@code /admin/api/**} 下，所以由
 * {@code AdminAuthInterceptor} 守着 —— 只有管理员能调，
 * 这三个方法里不需要再自己判断身份。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/admin/api/users")
@RequiredArgsConstructor
public class AdminUserManageController {

    private final AdminUserManageService userManageService;

    /**
     * 用户列表（分页）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，服务端会按配置封顶
     * @return 分页结果
     */
    @GetMapping
    public Result<PageVo<AdminUserListItemVo>> list(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(userManageService.list(page, size));
    }

    /**
     * 封禁 / 解封。
     *
     * <p>封禁会顺带把该用户所有已登录的会话注销掉 ——
     * 只改数据库状态是不够的，令牌在客户端还没过期。
     *
     * @param id      用户 id
     * @param request 目标状态
     * @return 空结果
     */
    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable("id") Long id,
                                     @Valid @RequestBody UserStatusRequest request) {
        userManageService.updateStatus(id, request.getStatus());
        return Result.success();
    }
}
