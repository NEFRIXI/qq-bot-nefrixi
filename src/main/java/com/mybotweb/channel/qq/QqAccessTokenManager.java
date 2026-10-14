package com.mybotweb.channel.qq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mybotweb.config.AppProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * QQ 访问凭证管理器 —— 负责拿到并维持 {@code access_token}。
 *
 * <p><b>它是干什么的？</b> 调腾讯任何 OpenAPI 都要在请求头带上
 * {@code Authorization: QQBot {access_token}}。而这个 token 不是常驻的，
 * 官方文档写得很明确：
 *
 * <blockquote>
 * 目前 {@code access_token} 生命周期默认 {@code 7200} 秒（2 小时），
 * 开发者需要在过期后自行刷新 {@code access_token}，保证调用链路权限正常。
 * </blockquote>
 *
 * <p>所以需要一个地方统一管着它：用的时候向本类要，本类保证给的是有效的；
 * 快过期了本类自己去换新的。调用方（比如发消息的代码）不需要关心这些。
 *
 * <p><b>一个很有用的官方特性</b>：
 * <blockquote>
 * 每次请求不会刷新新的 {@code access_token}，在有效期内重复获取会返回相同的值。
 * 在上一个 {@code access_token} 接近过期时间 60 秒内，获取时会获得一个新的，
 * 老的在这个 60 秒内仍然有效。
 * </blockquote>
 * 这段说明了两件事：
 * <ol>
 *   <li>频繁调用换取接口<b>不会</b>把 token 换乱，可以放心地"要用就换"</li>
 *   <li>新旧 token 有 60 秒的重叠期，所以刷新时不会出现"正好卡在过期瞬间"的空档</li>
 * </ol>
 * 因为有了第 1 条，本类的策略可以很简单：<b>有效期内直接用缓存的，
 * 剩余不足 5 分钟就去换新的</b>，不需要定时任务。
 *
 * <p>装配条件：{@code app.channel.type: qq}。用 mock 通道时这个 Bean 不存在。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.channel.type", havingValue = "qq")
public class QqAccessTokenManager {

    /**
     * 提前多久刷新（秒）。
     *
     * <p>官方给的重叠期是 60 秒，这里用 300 秒（5 分钟）是留足余量：
     * 万一那一刻网络抖动、或者换取接口偶发失败，还有充足的重试时间，
     * 不至于让某次发消息因为 token 过期而失败。
     */
    private static final long REFRESH_AHEAD_SECONDS = 300L;

    /** 换取 token 的超时时间（秒）。这是个很轻的请求，不该等太久 */
    private static final int REQUEST_TIMEOUT_SECONDS = 10;

    /** QQ 配置 */
    private final AppProperties.Qq config;

    /** HTTP 客户端 */
    private final RestClient restClient;

    /** 缓存的 token */
    private volatile String cachedToken;

    /** 缓存 token 的过期时间戳（毫秒，本地时钟） */
    private volatile long cachedExpiresAtMillis;

    public QqAccessTokenManager(AppProperties appProperties) {
        this.config = appProperties.getQq();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));

        this.restClient = RestClient.builder()
                .baseUrl(this.config.getBaseUrl())
                .requestFactory(factory)
                .build();

        log.info("QQ 凭证管理器已初始化，AppID={}，接口地址={}",
                this.config.getAppId(), this.config.getBaseUrl());
    }

    /**
     * 取一个当前有效的 access_token。
     *
     * <p>这是本类唯一的对外方法。调用方不用关心缓存、过期、刷新。
     *
     * @return 有效的 access_token
     * @throws IllegalStateException 拿不到 token 时抛出（通常是 AppSecret 没配或配错）
     */
    public String getToken() {
        String token = this.cachedToken;
        if (token != null && System.currentTimeMillis()
                < this.cachedExpiresAtMillis - REFRESH_AHEAD_SECONDS * 1000L) {
            return token;
        }
        return refresh();
    }

    /**
     * 去腾讯换取新的 token。
     *
     * <p>用 {@code synchronized} 保证并发时只换一次——如果多条消息同时到达、
     * 同时发现 token 过期，不加锁就会并发发好几个换取请求。
     * 虽然官方说重复换取返回同一个值（不会出错），但白跑几次请求没必要。
     *
     * @return 新的 access_token
     */
    private synchronized String refresh() {
        // 双重检查：可能在等锁的时候别人已经换好了
        String token = this.cachedToken;
        if (token != null && System.currentTimeMillis()
                < this.cachedExpiresAtMillis - REFRESH_AHEAD_SECONDS * 1000L) {
            return token;
        }

        if (isBlank(this.config.getAppSecret())) {
            // 这是最常见的配置遗漏，错误信息里直接说清楚该去哪儿填
            throw new IllegalStateException(
                    "AppSecret 还没配置。请去 QQ 开放平台 → 你的机器人 → 开发管理 复制 AppSecret，"
                            + "填到 application-local.yml 的 app.qq.app-secret 里。");
        }

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("appId", this.config.getAppId());
        requestBody.put("clientSecret", this.config.getAppSecret());

        log.debug("正在向腾讯换取 access_token...");

        AccessTokenResponse response;
        try {
            response = this.restClient.post()
                    .uri("/app/getAppAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(AccessTokenResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("换取 access_token 失败（网络异常）：" + e.getMessage(), e);
        }

        if (response == null) {
            throw new IllegalStateException("换取 access_token 失败：响应为空");
        }

        // 注意：这个接口的业务错误是放在响应体的 code 里的，
        // HTTP 状态码仍然是 200。所以必须检查 code，不能只看请求是否成功。
        if (response.getCode() != null && response.getCode() != 0) {
            throw new IllegalStateException(String.format(
                    "换取 access_token 被拒绝：code=%d，message=%s。"
                            + "常见原因：AppID/AppSecret 不对（100016），或机器人状态异常（100007）",
                    response.getCode(), response.getMessage()));
        }

        if (isBlank(response.getAccessToken())) {
            throw new IllegalStateException("换取 access_token 失败：响应里没有 access_token");
        }

        long expiresInSeconds = parseExpiresIn(response.getExpiresIn());
        this.cachedToken = response.getAccessToken();
        this.cachedExpiresAtMillis = System.currentTimeMillis() + expiresInSeconds * 1000L;

        // 只打印有效期，绝不打印 token 本身——日志可能被别处收集
        log.info("access_token 已刷新，有效期 {} 秒（约 {} 小时）",
                expiresInSeconds, expiresInSeconds / 3600);

        return this.cachedToken;
    }

    /**
     * 解析 {@code expires_in}。
     *
     * <p>文档里说它的类型是 {@code number}，但实际返回示例里是字符串
     * {@code "7200"}。为了两边都能接住，DTO 里声明成 {@code String}，
     * 在这里转成数字。这是对接第三方接口时的常规做法：
     * <b>以实际返回为准，而不是以文档的类型标注为准</b>。
     *
     * @param expiresIn 接口返回的有效期字段
     * @return 秒数；解析不了时返回默认值 7200
     */
    private long parseExpiresIn(String expiresIn) {
        if (expiresIn == null || expiresIn.isBlank()) {
            return 7200L;
        }
        try {
            return Long.parseLong(expiresIn.strip());
        } catch (NumberFormatException e) {
            log.warn("无法解析 expires_in 字段的值 '{}'，按默认 7200 秒处理", expiresIn);
            return 7200L;
        }
    }

    /**
     * 判断字符串是否为空。
     *
     * @param value 待判断的值
     * @return 为 {@code null} 或空白返回 {@code true}
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 换取 access_token 接口的响应体。
     *
     * <p>成功和失败共用一个类，因为腾讯把两种情况都塞进了同一个响应体：
     * 成功时给 {@code access_token}/{@code expires_in}，失败时给
     * {@code code}/{@code message}，且 HTTP 状态码都是 200。
     * 分两个类反而要写两次反序列化判断。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AccessTokenResponse {

        /** 拿到的凭证。字段名在 JSON 里是下划线风格，需要显式映射 */
        @JsonProperty("access_token")
        private String accessToken;

        /** 有效期（秒）。文档标 number，实际是字符串，所以用 String 接收 */
        @JsonProperty("expires_in")
        private String expiresIn;

        /** 业务错误码。成功时为 {@code null} 或 0 */
        private Integer code;

        /** 错误信息，仅用于人工排查 */
        private String message;
    }
}
