package com.mybotweb.blog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发评论 / 回复评论的请求 —— {@code POST /client/api/comments}。
 *
 * <p>发顶层评论：
 * <pre>{@code
 * { "articleId": 1, "content": "写得好" }
 * }</pre>
 *
 * <p>回复某条评论（楼中楼）：
 * <pre>{@code
 * { "articleId": 1, "content": "同意", "parentId": 12 }
 * }</pre>
 *
 * <p><b>{@code parentId} 传什么</b>：要回复的那条评论的 id，
 * 顶层评论和楼中楼回复都可以传 —— 传楼中楼回复时，后端会自动
 * 把新评论抬到所属的顶层评论下面，不会产生第三层。
 *
 * @author mybotweb
 */
@Data
public class CommentCreateRequest {

    /** 文章 id */
    @NotNull(message = "缺少文章 id")
    private Long articleId;

    /** 正文，纯文本 */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 1000, message = "评论最多 1000 字")
    private String content;

    /**
     * 要回复的评论 id。
     *
     * <p>{@code null} 或不传 = 发一条新的顶层评论。
     */
    private Long parentId;
}
