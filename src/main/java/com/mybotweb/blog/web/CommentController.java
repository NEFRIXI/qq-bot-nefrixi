package com.mybotweb.blog.web;

import com.mybotweb.blog.auth.UserContextHolder;
import com.mybotweb.blog.dto.CommentCreateRequest;
import com.mybotweb.blog.service.CommentService;
import com.mybotweb.blog.vo.CommentVo;
import com.mybotweb.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文章评论接口。
 *
 * <p>两个方法，权限要求不同：
 * <ul>
 *   <li>{@code GET} 看评论 —— <b>不用登录</b>，访客也能看</li>
 *   <li>{@code POST} 发评论 —— <b>必须登录</b></li>
 * </ul>
 *
 * <p>"哪个要登录"不是写在这里的，而是由
 * {@code UserAuthInterceptor.requiresLogin()} 统一判断 ——
 * 那里按"路径前缀 + HTTP 方法"分，规则集中一处，不会这边拦了那边漏。
 *
 * @author mybotweb
 */
@Slf4j
@RestController
@RequestMapping("/client/api/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 取某篇文章的评论树。
     *
     * @param articleId 文章 id
     * @return 顶层评论列表，每条带自己的楼中楼
     */
    @GetMapping
    public Result<List<CommentVo>> list(@RequestParam("articleId") Long articleId) {
        return Result.success(commentService.listByArticle(articleId));
    }

    /**
     * 发评论或回复。
     *
     * <p>身份从 {@code UserContextHolder.require()} 取 —— 能走到这里
     * 说明拦截器已经验过了。require() 而不是 get()，是为了万一哪天
     * 拦截器的路径规则配错了，这里会立刻 401 而不是用 null 继续跑。
     *
     * @param request 请求体
     * @return 新建的评论
     */
    @PostMapping
    public Result<CommentVo> create(@Valid @RequestBody CommentCreateRequest request) {
        CommentVo created = commentService.create(
                UserContextHolder.require(),
                request.getArticleId(),
                request.getContent(),
                request.getParentId());
        return Result.success(created);
    }
}
