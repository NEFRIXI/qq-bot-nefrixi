package com.mybotweb.blog.web;

import com.mybotweb.blog.auth.AdminAuthService;
import com.mybotweb.blog.auth.AdminContextHolder;
import com.mybotweb.blog.auth.AdminPrincipal;
import com.mybotweb.blog.dto.LoginRequest;
import com.mybotweb.blog.vo.AdminInfoVo;
import com.mybotweb.blog.vo.LoginVo;
import com.mybotweb.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台的登录、登出、查当前身份。
 *
 * <p>三个接口都不需要事先登录，其中 {@code /login} 更是必须能匿名访问 ——
 * 它是"从没有令牌变成有令牌"的入口（拦截器里专门排除了它）。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    /**
     * 登录，换一个令牌。
     *
     * <p>示例：
     * <pre>
     * POST /admin/api/login
     * { "username": "admin", "password": "xxxx" }
     * </pre>
     *
     * <p>成功时 { data: { token, expireSeconds, username, nickname } }，
     * 前端把 token 存进 localStorage 之后，后续请求带在
     * {@code Authorization: Bearer <token>} 头上。
     *
     * @param request 用户名和密码
     * @return 令牌
     */
    @PostMapping("/login")
    public Result<LoginVo> login(@Valid @RequestBody LoginRequest request) {
        return Result.success("登录成功", adminAuthService.login(request));
    }

    /**
     * 登出 —— 让当前令牌立刻失效。
     *
     * <p>这个接口<b>需要带令牌</b>才能调用（它在拦截器的保护范围里）。
     * 看起来有点怪："我都要登出了为什么还得证明我是我" ——
     * 因为服务端得知道要吊销<b>哪一条</b>会话，而那个信息就在令牌里。
     *
     * <p>前端登出时要注意顺序：<b>先调这个接口，成功后再删本地令牌</b>。
     * 反过来的话请求发不出去，服务端那条记录还留着，
     * 那串令牌在有效期内仍然能用。
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        // 拦截器已经校验过令牌并放进 ThreadLocal 了，这里直接取。
        // 用 get() 而不是 require()：能走到这里说明一定已经登录，
        // 但万一真为 null，登出失败也不该报 500 —— 前端照样会清本地令牌
        adminAuthService.logout(AdminContextHolder.get());
        return Result.success();
    }

    /**
     * 查当前登录的是谁。
     *
     * <p>前端每次打开后台页面时先调这个：
     * <ul>
     *   <li>返回 401 → 本地令牌已失效，跳登录页</li>
     *   <li>返回成功 → 顺便拿到昵称显示在右上角</li>
     * </ul>
     * 比"直接请求业务接口然后靠报错判断"更清楚，
     * 也不会因为某个业务接口恰好出错就误判成没登录。
     *
     * @return 当前管理员的公开信息
     */
    @GetMapping("/me")
    public Result<AdminInfoVo> me() {
        // require()：没登录会抛 401，由全局异常处理器转成统一响应体
        AdminPrincipal principal = AdminContextHolder.require();
        return Result.success(AdminInfoVo.from(principal));
    }
}
