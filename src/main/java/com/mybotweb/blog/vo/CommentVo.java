package com.mybotweb.blog.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条评论（含它的楼中楼）。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么是"树"但只有两层</b>
 *
 * <p>{@link #replies} 在顶层评论上是有内容的，在楼中楼回复上<b>永远是空列表</b>
 * （不是 null —— 空列表让前端不用判空）：
 *
 * <pre>
 * 评论 A（楼）
 *   ├─ 回复 B  replies = []
 *   └─ 回复 C  replies = []
 * 评论 D（楼）
 *   └─ 回复 E  replies = []
 * </pre>
 *
 * <p>这个类型本身不阻止你再套一层，但 {@code CommentService} 在写入时
 * 会把所有回复的 {@code parentId} 归到顶层评论上，所以数据库里
 * 不可能出现第三层。约束在写入侧，不在类型侧 —— 因为类型要么管不住，
 * 要么就得建两个类（CommentVo / ReplyVo），多一份东西要同步。
 *
 * @author mybotweb
 */
@Data
public class CommentVo {

    /** 评论 id */
    private Long id;

    /** 所属文章 id */
    private Long articleId;

    /** 评论正文 */
    private String content;

    /** 作者 id —— 前端用它判断"这条是不是我发的"（决定显示不显示删除按钮） */
    private Long userId;

    /**
     * 作者显示名。
     *
     * <p>后端算好直接给出（昵称优先，没有就用登录名），
     * 而不是让前端拿着 userId 再去查一次 —— 一页评论 20 条，
     * 前端要发 20 个请求就太蠢了。
     */
    private String authorName;

    /** 发表时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 被回复的人的 id。
     *
     * <p>只有楼中楼里有值，用来显示"回复 @某某"。
     * 它不参与层级判断（层级只看这个对象在 {@link #replies} 里还是在顶层列表里）。
     */
    private Long replyToUserId;

    /** 被回复的人的显示名，和 {@link #replyToUserId} 配套 */
    private String replyToName;

    /**
     * 楼中楼的回复列表。
     *
     * <p><b>约定：永远不为 null。</b> 没有回复时是空列表。
     * 用 {@code new ArrayList<>()} 初始化，保证这个约定不依赖调用方。
     */
    private List<CommentVo> replies = new ArrayList<>();
}
