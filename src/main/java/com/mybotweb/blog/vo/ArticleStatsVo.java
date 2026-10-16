package com.mybotweb.blog.vo;

import lombok.Data;

import java.util.List;

/**
 * 访问统计 —— 后台首页显示的那几个数。
 *
 * <p><b>这些数从哪来</b>：
 * <ul>
 *   <li>文章篇数 —— 数据库（{@code article} 表）</li>
 *   <li>总浏览、总点赞 —— Redis 里所有 {@code article:view_count:*} /
 *       {@code article:like_count:*} 加起来</li>
 * </ul>
 *
 * <p>注意它们是<b>"全部已发布文章之和"</b>，而单独的计数没有按天留档，
 * 所以算不出"今日浏览量"这种环比数据。要做的话得另外按天记一份，
 * 现在不需要。
 *
 * @author mybotweb
 */
@Data
public class ArticleStatsVo {

    /** 文章总数（含草稿） */
    private long totalArticles;

    /** 已发布篇数 */
    private long publishedArticles;

    /** 草稿篇数 */
    private long draftArticles;

    /** 所有已发布文章的浏览量之和 */
    private long totalViews;

    /** 所有已发布文章的点赞数之和 */
    private long totalLikes;

    /** 浏览量最高的几篇 */
    private List<ArticleRankItemVo> topViewed;

    /** 点赞数最高的几篇 */
    private List<ArticleRankItemVo> topLiked;

    /** 点赞最多的那篇的标题，一篇都没有时为 null。前端用它做一句话总结 */
    private String mostLikedTitle;

    /** 浏览量最多的那篇的标题 */
    private String mostViewedTitle;
}
