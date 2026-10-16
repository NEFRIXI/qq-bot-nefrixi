package com.mybotweb.blog.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 的分页插件。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>⚠ 不加这个类，分页是"假的"，而且不报错</b>
 *
 * <p>MyBatis-Plus 的分页<b>不是自动生效的</b>，必须显式注册下面这个拦截器。
 * 不注册时的表现特别容易骗人：
 * <pre>
 * articleMapper.selectPage(new Page&lt;&gt;(1, 10), wrapper);
 * </pre>
 * 这句话<b>不报错</b>，但返回的是<b>全部数据</b>，
 * 而且 {@code page.getTotal()} 是 0。
 *
 * <p>于是你会看到：列表页显示了全部 37 篇文章（而不是 10 篇），
 * 底部的"共 0 篇"是错的，翻页按钮点不动。
 * 数据量小的时候（十几篇）甚至不会觉得哪里不对 ——
 * 等文章多到几百篇、查询开始变慢时才发现，那时又要回头查很久。
 *
 * <p>报错反而是好事，这种"看起来正常"的问题最难查。所以这个类必须留着。
 *
 * @author mybotweb
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 的拦截器链。
     *
     * <p>目前链上只有一个分页拦截器。以后要加别的（比如乐观锁、
     * 防全表更新删除），也是在这里 {@code addInnerInterceptor}，
     * 顺序有讲究 —— 分页应当放在最后，让前面的拦截器先改完 SQL。
     *
     * @return 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // DbType.MYSQL 决定了分页 SQL 用哪种写法：
        // MySQL 用 LIMIT offset, size，Oracle 用 ROWNUM，SQL Server 用 TOP。
        // 写错了会生成别的数据库才认的 SQL，直接报语法错误
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
