package com.mybotweb.blog.web;

import com.mybotweb.blog.auth.UserContextHolder;
import com.mybotweb.blog.dto.PostCreateRequest;
import com.mybotweb.blog.service.UserPostService;
import com.mybotweb.blog.vo.ArticleListItemVo;
import com.mybotweb.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 普通用户的发帖接口。
 *
 * <p>三个动作，全部需要登录（由
 * {@code UserAuthInterceptor.requiresLogin()} 统一拦）：
 * <ul>
 *   <li>{@code POST /client/api/posts} —— 发帖</li>
 *   <li>{@code GET  /client/api/posts/mine} —— 我发过的</li>
 *   <li>{@code DELETE /client/api/posts/{id}} —— 删自己的</li>
 * </ul>
 *
 * <p><b>发帖返回的是文章 id，不是完整文章</b>：
 * 前端拿到 id 可以直接跳详情页；要是返回整篇文章，
 * 正文也跟着回一遍，白传几十 KB（服务器带宽只有 3 Mbps）。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/client/api/posts")
@RequiredArgsConstructor
public class UserPostController {

    private final UserPostService userPostService;

    /**
     * 发一篇帖子。
     *
     * @param request 标题和正文
     * @return 新文章的 id
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody PostCreateRequest request) {
        return Result.success(userPostService.create(UserContextHolder.require(), request));
    }

    /**
     * 我发过的帖子。
     *
     * @return 列表（不含正文）
     */
    @GetMapping("/mine")
    public Result<List<ArticleListItemVo>> mine() {
        return Result.success(userPostService.listMine(UserContextHolder.require()));
    }

    /**
     * 删除自己的帖子。
     *
     * <p>归属校验在 Service 里 —— 这里只管把身份传下去。
     *
     * @param id 文章 id
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        userPostService.delete(UserContextHolder.require(), id);
        return Result.success();
    }
}
