package com.mybotweb.blog.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章列表里的一项。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 这里没有 {@code content} 字段，是刻意的</b>
 *
 * <p>列表接口一次返回 10 篇文章。如果每篇都带上正文，
 * 一页就是几百 KB —— 而服务器带宽只有 <b>3 Mbps</b>，
 * 换算下来几百 KB 要传一两秒，翻页会明显卡顿。
 *
 * <p>列表真正需要显示的是：标题、摘要、时间、点赞数、浏览量。
 * 正文等用户点进详情页时再单独取那一篇。
 * 这个类里<b>根本没有 content 字段</b>，所以想带也带不出去 ——
 * 靠字段不存在来保证，比靠"记得在 SQL 里别查它"可靠。
 *
 * @author mybotweb
 */
@Data
public class ArticleListItemVo {

    /** 文章 id */
    private Long id;

    /** URL 短名 */
    private String slug;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /**
     * 状态：0 = 草稿，1 = 已发布。
     *
     * <p>前台列表里这个值恒为 1（草稿根本查不出来），
     * 所以前台页面可以忽略它。后台列表要靠它显示"草稿"小标签。
     */
    private Integer status;

    /**
     * 发布时间。
     *
     * <p>{@code @JsonFormat} 指定了输出格式。加这个注解是因为
     * application.yml 里配的 {@code spring.jackson.date-format}
     * <b>只对旧的 {@code java.util.Date} 生效</b>，
     * 对 {@code LocalDateTime} 无效 —— 不加注解的话会输出成
     * {@code 2026-10-17T00:55:04} 这种带 T 的样子，
     * 前端还得自己替换掉那个 T。
     *
     * <p>草稿状态下这个值是 {@code null}，前端要判一下。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;

    /** 最后修改时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    /**
     * 点赞数。
     *
     * <p>它来自 Redis 的 {@code article:like_count:{id}}，不是数据库字段。
     * 用基本类型 {@code long}：Redis 里没有这个 key 时算 0，
     * 不存在"没有这个值"的情况。
     */
    private long likeCount;

    /** 浏览量，同样来自 Redis（{@code article:view_count:{id}}） */
    private long viewCount;
}
