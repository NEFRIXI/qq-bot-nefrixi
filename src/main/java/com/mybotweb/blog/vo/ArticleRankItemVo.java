package com.mybotweb.blog.vo;

import lombok.Data;

/**
 * 排行榜里的一篇文章。
 *
 * <p>两个榜（浏览量榜、点赞榜）共用这个类：不管在哪个榜里，
 * 两个计数都是有的，只是排序依据不同，所以不用建两个类。
 *
 * @author mybotweb
 */
@Data
public class ArticleRankItemVo {

    /** 文章 id */
    private Long id;

    /**
     * URL 短名。
     *
     * <p>带上它是为了让后台能直接给出"去前台看看"的链接 ——
     * 只有 id 的话前端拼不出正确的地址。
     */
    private String slug;

    /** 标题 */
    private String title;

    /** 浏览量 */
    private long viewCount;

    /** 点赞数 */
    private long likeCount;
}
