-- ============================================================
-- V3：普通用户 + 评论 + 文章作者
--
-- 【这次动了三张表，分三个部分看】
--   一、app_user   普通用户（和 admin_user 完全分开）
--   二、comment    评论（楼中楼，但只存两层）
--   三、article    加一列，记录文章作者
--
-- 【最重要的一条纪律】
-- 这个脚本一旦执行过就不要再改。Flyway 记了校验和，
-- 改了下次启动报 "Migration checksum mismatch"。
-- 要调整就新加 V4。
-- ============================================================


-- ============================================================
-- 一、普通用户表
--
-- 【为什么另建一张表，而不是给 admin_user 加一列 role】
--   表面上"一张表 + role 字段"更省事，但两者根本不是一个东西：
--     管理员：数量极少（就一两个人），由站点所有者维护，
--             能删文章、能看服务器状态
--     普通用户：数量不设上限，任何人都能自助注册，
--             只能管自己发的帖子和评论
--   混在一张表里，最危险的是"某个查询忘了带 role 条件"——
--   一次疏漏就是把后台权限漏给全网。分成两张表之后，
--   越权在类型层面就不可能发生，不需要靠每次查询都记得过滤。
--
-- 【status 为什么用 0/1 而不是软删除时间】
--   管理员"封禁"用户是个可逆动作：先停用，以后可能恢复。
--   软删除语义是"这条记录没了"，不适合表达"人还在，但先不让说话"。
-- ============================================================

CREATE TABLE `app_user`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(50)  NOT NULL COMMENT '登录名，唯一',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，不是明文',
    `nickname`      VARCHAR(50)  DEFAULT NULL COMMENT '显示名，评论和文章上显示这个',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=正常 1=已封禁',
    `created_at`    DATETIME     NOT NULL COMMENT '注册时间',
    `updated_at`    DATETIME     NOT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_user_username` (`username`),
    KEY `idx_app_user_status` (`status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='普通用户（前台注册）';


-- ============================================================
-- 二、评论表（楼中楼）
--
-- 【"只递归一层"是怎么实现的】
--   需求是：能评论，也能回复评论，但回复的回复不再往下嵌套
--   （也就是 B 站那种"楼 + 楼中楼"的形式）。
--
--   实现上只存两层，靠 parent_id 的语义约束：
--     · 顶层评论         parent_id IS NULL
--     · 对评论的回复      parent_id = 那条顶层评论的 id
--
--   【关键约定】parent_id 永远指向顶层评论，不指向另一条回复。
--   当用户去回复一条"回复"时，后端把 parent_id 改写成那条回复
--   所属的顶层评论 id，同时用 reply_to_user_id 记住"其实是在回谁"。
--
--   【为什么不用 parent_id 直接链式指向被回复的那条】
--   链式指向就是一棵任意深度的树，取一棵树要递归查询或者全表捞出来
--   在内存里拼，楼层一多就慢。扁平成两层之后，拉一页评论只要
--   一条 SQL（按 article_id 查，再按 parent_id 分组），
--   楼中楼的"回复 @某人"效果靠 reply_to_user_id 就能表达。
--
-- 【为什么不存 user 快照】
--   评论只存 user_id，显示昵称时 join 用户表。
--   代价是用户改昵称后历史评论也跟着变 —— 这正是想要的：
--   评论属于这个人，不应该是某个时刻的名字快照。
-- ============================================================

CREATE TABLE `comment`
(
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `article_id`       BIGINT        NOT NULL COMMENT '所属文章',
    `user_id`          BIGINT        NOT NULL COMMENT '发评论的用户',
    `parent_id`        BIGINT        DEFAULT NULL COMMENT '顶层评论为 NULL；回复时指向被回复的【顶层评论】id',
    `reply_to_user_id` BIGINT        DEFAULT NULL COMMENT '被回复的那个人的 id（楼中楼里显示"回复 @某某"）',
    `content`          VARCHAR(1000) NOT NULL COMMENT '评论正文，纯文本',
    `status`           TINYINT       NOT NULL DEFAULT 0 COMMENT '0=正常 1=已隐藏（管理员操作）',
    `created_at`       DATETIME      NOT NULL COMMENT '发表时间',
    PRIMARY KEY (`id`),
    -- 前台固定的查询形态：某篇文章下、按顶层评论分组、各自按时间排。
    -- article_id 放最前（等值），后面才是分组和排序要用的列
    KEY `idx_comment_article` (`article_id`, `parent_id`, `created_at`),
    -- "这个用户发过哪些评论"，管理端和防刷都要用
    KEY `idx_comment_user` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='文章评论（两层：楼 + 楼中楼）';


-- ============================================================
-- 三、文章作者
--
-- 【为什么用可空列，而不是 backfill 成某个"系统用户"】
--   改动前所有文章都是管理员写的，本来就没有"作者"这个概念。
--   给它们补一个虚构的用户，会让"按作者筛选"多出一堆假数据。
--   留 NULL 表示"管理员/历史文章"，语义干净：
--     查询用 author_user_id = ? 自然就排除了它们。
--
-- 【为什么不拆成 author_id + author_type】
--   多一个 type 列，就要在每个查询里都带上 "author_type = 'USER' AND ..."，
--   又一次把"别忘了过滤"变成了正确性的前提。
--   NULL 自带这个语义，不需要额外条件。
-- ============================================================

ALTER TABLE `article`
    ADD COLUMN `author_user_id` BIGINT DEFAULT NULL
        COMMENT '作者：NULL=管理员/历史文章；有值=普通用户 id';

-- 加索引前先说明它服务哪个查询：
--   用户主页"我发过的帖子" + 管理端"某人发过什么"
ALTER TABLE `article`
    ADD KEY `idx_article_author` (`author_user_id`, `published_at`);
