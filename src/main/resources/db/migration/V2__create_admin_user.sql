-- ============================================================
-- V2：后台管理员表
--
-- 【为什么密码列叫 password_hash 而不是 password】
-- 数据库里绝不存明文密码。存的是 BCrypt 哈希：
--   同一个密码每次哈希出来的结果都不一样（内部掺了随机盐），
--   所以没法用"两个用户的哈希相同"来推断他们密码相同。
--   校验时用 BCrypt.matches(用户输入的明文, 库里的哈希) 反查。
-- 结果就是：就算整个库被人拖走，也算不出你的密码是什么。
--
-- 【为什么长度给到 100】
-- BCrypt 的哈希固定 60 个字符，100 是留的余量 ——
-- 以后万一想换成 Argon2（更长的哈希），不用再改表结构。
--
-- 【这张表目前只会有一行】
-- 应用启动时如果发现表是空的，就用 app.blog.admin 配的那组账号密码
-- 建出第一个管理员。以后要加人、改密码，走后台接口。
-- 表结构支持多行，不是为了现在用，是为了以后不用改表。
-- ============================================================

CREATE TABLE `admin_user`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(50)  NOT NULL COMMENT '登录名',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希后的密码，不是明文',
    `nickname`      VARCHAR(50) DEFAULT NULL COMMENT '显示名，如"站长"',
    `created_at`    DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL COMMENT '最后修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='后台管理员';
