package com.mybotweb.blog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一条评论 —— 对应 {@code comment} 表。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>两层结构：楼 + 楼中楼</b>
 *
 * <p>需求是"能评论、能回复评论，但回复不再往下嵌套"（B 站那种形式）。
 * 落到数据上只有两种形态：
 *
 * <pre>
 *   顶层评论    parent_id = null         ← 楼
 *   楼中楼回复  parent_id = 某个顶层 id   ← 楼里的一条回复
 * </pre>
 *
 * <p><b>关键约定：{@link #parentId} 永远指向一条顶层评论。</b>
 * 当用户去回复一条"回复"时，Service 层会把 parentId 改写为那条回复
 * 所属的<b>顶层评论</b> id，同时用 {@link #replyToUserId} 记住
 * "其实是在回谁"。这样数据库里永远只有两层，不会出现第三层。
 *
 * <p>为什么不改成链式指向（parent 指向被回复的那一条）：
 * 那就是一棵任意深度的树，取一棵树要么递归查询、要么全表捞出来在内存拼，
 * 楼层一多就慢。扁平两层之后，拉一页评论只要一条 SQL 再按 parentId 分组。
 *
 * @author mybotweb
 */
@Data
@TableName("comment")
public class Comment {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文章 id */
    private Long articleId;

    /** 发评论的用户 id（指向 {@code app_user}） */
    private Long userId;

    /**
     * 父评论 id。
     *
     * <p>{@code null} 表示这是一条顶层评论（楼）。
     * 非 null 时<b>一定</b>指向一条顶层评论，不会是另一条回复 ——
     * 这是"只递归一层"在数据层面的保证。
     */
    private Long parentId;

    /**
     * 被回复用户的 id。
     *
     * <p>只在楼中楼里有值，用来渲染"回复 @某某"。
     * 它是<b>展示用</b>的，不参与层级判断 —— 层级只看 {@link #parentId}。
     */
    private Long replyToUserId;

    /** 评论正文，纯文本（不做 Markdown 渲染） */
    private String content;

    /** 状态：0 = 正常，1 = 已隐藏（管理员操作） */
    private Integer status;

    /** 发表时间 */
    private LocalDateTime createdAt;
}
