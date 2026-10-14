package com.mybotweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 项目启动类。
 *
 * <p>{@code @SpringBootApplication} 是一个组合注解，等价于下面三个：
 * <ul>
 *   <li>{@code @SpringBootConfiguration} —— 声明这是一个配置类</li>
 *   <li>{@code @EnableAutoConfiguration} —— 开启自动配置（Spring Boot 的看家本领：
 *       它根据 classpath 上有什么依赖，自动帮你装配对应的 Bean。比如发现有
 *       spring-boot-starter-web，就自动配好 Tomcat 和 Spring MVC）</li>
 *   <li>{@code @ComponentScan} —— 扫描<b>当前包及所有子包</b>下的
 *       {@code @Component}/{@code @Service}/{@code @RestController} 等注解，注册成 Bean</li>
 * </ul>
 *
 * <p><b>注意扫描范围</b>：因为 {@code @ComponentScan} 只扫本类所在包及其子包，
 * 所以这个类必须放在<b>最外层包</b>（{@code com.mybotweb}），
 * 所有业务代码都要放在它的子包里，否则不会被扫描到、注入会失败。
 *
 * <p>{@code @ConfigurationPropertiesScan} 让 Spring 自动扫描
 * {@code @ConfigurationProperties} 标注的配置类（如 {@code AppProperties}），
 * 这样就不必在每个配置类上再写 {@code @EnableConfigurationProperties}。
 *
 * @author mybotweb
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MybotwebApplication {

    /**
     * 程序入口。
     *
     * <p>{@code SpringApplication.run} 返回的是 Spring 的容器对象（ApplicationContext），
     * 我们启动后不需要它，所以这里不接收返回值。
     *
     * @param args 命令行参数，例如 {@code --server.port=9090}
     */
    public static void main(String[] args) {
        SpringApplication.run(MybotwebApplication.class, args);
    }
}
