-- ============================================================
-- V1：文章表
--
-- 【Flyway 的命名规则，写错会启动报错】
--   V1        版本号，必须唯一且递增
--   __        两个下划线，分隔"版本号"和"描述"（写成一个不行）
--   create_article  描述部分，随便写，只是给人看的
--
-- 【最重要的一条纪律】
-- 这个脚本一旦被执行过，就【不要再改它的内容】。
-- Flyway 会把每个脚本的校验和记进 flyway_schema_history 表，
-- 改了内容下次启动会报 "Migration checksum mismatch"。
-- 要调整表结构就新加一个 V2、V3……，不要回头改 V1。
-- ============================================================

CREATE TABLE `article`
(
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `slug`         VARCHAR(120) NOT NULL COMMENT 'URL 里用的英文短名，如 why-bot-said-402',
    `title`        VARCHAR(200) NOT NULL COMMENT '标题',
    `summary`      VARCHAR(300) DEFAULT NULL COMMENT '摘要，列表页显示；留空时可从正文自动截取',
    `content`      MEDIUMTEXT   NOT NULL COMMENT '正文，存 Markdown 原文，不存渲染后的 HTML',
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=草稿 1=已发布',
    `created_at`   DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`   DATETIME     NOT NULL COMMENT '最后修改时间',
    `content_hash` CHAR(64)     DEFAULT NULL COMMENT '正文的 SHA-256，用来判断内容是否真的变了',
    `published_at` DATETIME     DEFAULT NULL COMMENT '发布时间，列表按它倒序排',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_article_slug` (`slug`),
    KEY `idx_article_status_published` (`status`, `published_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='博客文章';

-- ------------------------------------------------------------
-- 几个字段为什么这么设计，以后别改错：
--
-- 1) content 存 Markdown 原文，不存渲染后的 HTML
--    源格式只能有一份。存了 HTML 就等于把内容和排版焊死，
--    以后想换渲染器或改样式，历史文章全得重来。
--    渲染在服务端读取时做（MarkdownRenderer），结果可以缓存。
--
-- 2) 列表按 published_at 排序，不是 created_at
--    草稿可能是三个月前写的，今天才发布。按创建时间排的话，
--    新发布的文章会插在列表中间，看起来像乱序。
--
-- 3) updated_at 和 content_hash 现在不参与任何逻辑，是【预留字段】
--    计划第二步做「QQ 机器人读网站文章」的增量同步：
--    机器人记住上次同步到的 updated_at，只拉比它新的；
--    content_hash 用来判断"这篇文章虽然改了时间戳，但正文其实没动"。
--    现在不留、以后再加，就要改表 + 回填历史数据，代价大得多。
--
-- 4) 索引 idx_article_status_published 的列顺序不能反
--    前台查询固定是"status = 1 且按 published_at 倒序"，
--    所以 status 放前面（等值条件）、published_at 放后面（范围/排序）。
--    反过来写，MySQL 就用不上这个索引了。
-- ------------------------------------------------------------
