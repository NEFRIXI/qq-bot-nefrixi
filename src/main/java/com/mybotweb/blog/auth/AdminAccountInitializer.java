package com.mybotweb.blog.auth;

import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.entity.AdminUser;
import com.mybotweb.blog.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 首次启动时创建初始管理员。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么需要这一步</b>
 *
 * <p>后台是登录才能用的，但"第一个账号"总得有人来建。
 * 如果在网页上开放注册，那任何人都能给自己开一个后台账号；
 * 如果让别人手工往数据库里插一行 BCrypt 哈希，那更不现实
 * （哈希没法手算）。
 *
 * <p>所以做法是：<b>账号密码写在配置里，应用启动时自动建一次。</b>
 * 建完之后配置里那几行就再也不起作用了 —— 改密码要走后台。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>为什么用 {@code ApplicationRunner} 而不是 {@code @PostConstruct}</b>
 *
 * <p>这一步要先查 {@code admin_user} 表。而建表是 Flyway 干的，
 * 两者都是"启动阶段"要做的事，谁先谁后取决于 Bean 的初始化顺序 ——
 * 用 {@code @PostConstruct} 有可能排在 Flyway 前面，
 * 于是第一次启动就报"表不存在"，第二次启动反而正常了
 * （因为表已经被上一次建出来了）。这种"错一次就好了"的 bug 最难查。
 *
 * <p>{@code ApplicationRunner} 是 Spring Boot 在<b>整个应用完全启动之后</b>
 * 才调的回调，那时 Flyway 一定已经跑完。顺序是确定的。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccountInitializer implements ApplicationRunner {

    private final BlogProperties blogProperties;

    private final AdminUserMapper adminUserMapper;

    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!blogProperties.isEnabled()) {
            log.info("博客功能未开启（app.blog.enabled=false），跳过初始管理员检查");
            return;
        }

        try {
            initializeIfAbsent();
        } catch (Exception e) {
            // 【这里必须吞掉异常】不能让"建管理员失败"把整个应用搞崩 ——
            // 那会连带 QQ 机器人一起起不来。机器人是主力功能，
            // 数据库有问题时它也该照常工作。
            // 后果（后台登不进去）在日志里说清楚了，用户能自己判断。
            log.error("检查初始管理员时出错，博客后台可能无法登录", e);
        }
    }

    /**
     * 表里没有账号时，用配置里的用户名密码建一个。
     */
    private void initializeIfAbsent() {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) {
            log.info("admin_user 表里已有 {} 个账号，跳过初始管理员创建", count);
            return;
        }

        String username = blogProperties.getAdmin().getUsername();
        String rawPassword = blogProperties.getAdmin().getPassword();

        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("""
                    
                    ================================================================
                    admin_user 表是空的，但没配置初始密码，无法创建管理员。
                    ----------------------------------------------------------------
                    配置项：app.blog.admin.password
                    请填一个密码（写在 application-local.yml 或 application-prod.yml 里，
                    那两个文件不进 git），然后重启应用。
                    ================================================================
                    """);
            return;
        }

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setNickname(blogProperties.getAdmin().getNickname());
        // 存进去之前先哈希。这一步之后，明文密码只存在于配置文件里，
        // 数据库里从头到尾没有过
        user.setPasswordHash(passwordEncoder.encode(rawPassword));

        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        adminUserMapper.insert(user);

        log.info("""
                
                ================================================================
                已创建初始管理员：{}
                ----------------------------------------------------------------
                密码取自配置（app.blog.admin.password），数据库里存的是 BCrypt 哈希。
                以后改密码请用后台功能，改配置文件不再生效。
                ================================================================
                """, username);
    }
}
