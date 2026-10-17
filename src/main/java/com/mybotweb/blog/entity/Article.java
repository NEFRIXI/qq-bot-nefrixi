package com.mybotweb.blog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文章实体 —— 对应数据库的 {@code article} 表。
 *
 * <p>表结构由 Flyway 脚本 {@code db/migration/V1__create_article.sql} 建立。
 * 字段和列名靠 MyBatis-Plus 的"下划线转驼峰"自动对应：
 * Java 的 {@code createdAt} ↔ 数据库的 {@code created_at}，
 * 所以这里不用给每个字段写 {@code @TableField}。
 *
 * <p><b>{@code @TableId(type = IdType.AUTO)} 是什么意思</b>
 * 主键由数据库的 {@code AUTO_INCREMENT} 生成，MyBatis-Plus 在 insert 之后
 * 会把生成出来的 id 回填到这个对象上。所以
 * {@code articleMapper.insert(article)} 执行完，直接
 * {@code article.getId()} 就能拿到新 id，不用再查一次。
 *
 * <p><b>关于 content 字段</b>：它存的是 Markdown <b>原文</b>，不是渲染后的 HTML。
 * 源格式只能有一份 —— 存了 HTML 就等于把内容和排版焊死，
 * 以后想改样式或换渲染器，历史文章全得重来。
 * 渲染发生在读取时（{@code MarkdownRenderer}），结果只存在于响应里。
 *
 * @author mybotweb
 */
@Data
@TableName("article")
public class Article {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * URL 里用的英文短名，如 {@code why-bot-said-402}。
     *
     * <p>数据库上有唯一索引，所以重复插入会直接报错 ——
     * Service 层会先查一次给出友好提示，但唯一索引才是最后那道保险：
     * 两个请求同时提交同一个 slug 时，先查后插是拦不住的。
     */
    private String slug;

    /** 标题 */
    private String title;

    /**
     * 摘要，列表页显示。
     *
     * <p>允许为空：留空时由 Service 从正文自动截一段
     * （见 {@code ArticleService.resolveSummary}）。
     * 之所以要留空自动生成，是因为"每写一篇都要自己想摘要"很烦，
     * 而 Markdown 正文的前几十个字通常就能当摘要用。
     */
    private String summary;

    /** 正文，Markdown 原文 */
    private String content;

    /**
     * 状态，取值见 {@link ArticleStatus}：0 = 草稿，1 = 已发布。
     *
     * <p>用包装类型 {@code Integer} 而不是 {@code int}：
     * 包装类型能表示"数据库里是 NULL"这种情况，而基本类型只能表示 0（草稿）。
     * 这两者在排查数据问题时差别很大。
     */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后修改时间。每次编辑都会更新 */
    private LocalDateTime updatedAt;

    /**
     * 正文的 SHA-256 十六进制串（64 个字符）。
     *
     * <p><b>现在不参与任何逻辑，是预留字段。</b>
     * 计划第二步做"QQ 机器人读网站文章"的增量同步时用它判断
     * "这篇虽然时间戳变了，但正文其实没动"，避免重复同步。
     * 现在不留，以后加就要改表 + 回填所有历史文章。
     */
    private String contentHash;

    /**
     * 发布时间。
     *
     * <p><b>列表排序用的是它，不是 createdAt。</b>
     * 草稿可能是三个月前写的、今天才发布；按创建时间排的话，
     * 新发布的文章会插在列表中间，看起来像乱序。
     *
     * <p>草稿状态下这个字段是 {@code null}，发布时才赋值。
     */
    private LocalDateTime publishedAt;

    /**
     * 作者（普通用户 id）。
     *
     * <p>{@code null} 表示<b>管理员发的</b>或者改动前就存在的历史文章 ——
     * 那时候还没有"作者"这个概念。
     *
     * <p>用可空列而不是"补一个系统用户"：补假数据会让"按作者筛选"
     * 多出一堆没意义的记录。NULL 自带"这不属于任何普通用户"的语义，
     * 查询时 {@code author_user_id = ?} 自然就把它们排除了，
     * 不用再额外记得加一个 type 条件。
     */
    private Long authorUserId;
}
