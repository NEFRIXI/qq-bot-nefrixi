package com.mybotweb.blog.auth;

import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.entity.AdminUser;
import com.mybotweb.blog.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * 登录令牌（JWT）的签发与校验。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>JWT 到底是什么，一句话</b>
 *
 * <p>它就是一张<b>防伪的纸条</b>，上面写着"这人是 admin，有效期到某时某刻"。
 * 服务器签发时不存任何东西（无状态），下次请求带着它来，
 * 服务器用密钥验一下签名对不对、过期没有 —— 对就认。
 *
 * <p>它长这样，三段用点分开：
 * <pre>
 * eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJhZG1pbiJ9 . dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk
 *      头部（算法）          载荷（内容，只做了编码）        签名
 * </pre>
 *
 * <p><b>⚠ 载荷是"编码"不是"加密"</b>：中间那段用 Base64 解一下就能看到原文，
 * 所以里面<b>绝不能放密码、密钥</b>这类东西。本类只放用户名、昵称、
 * 管理员 id 和一个随机标识 —— 这些都是登录后本来就要显示给本人的信息。
 * 防伪靠的是第三段签名：没有密钥就伪造不出来。
 *
 * <p>═══════════════════════════════════════════════════════════
 * <p><b>它的短板，以及本项目的补法</b>
 *
 * <p>JWT 签出去就收不回来 —— 想"登出"或者"改了密码让旧令牌立刻失效"，
 * 光靠它做不到。所以项目另外用 Redis 记一份已签发的令牌，
 * 登出时删掉（见 {@link TokenRevokeStore}）。
 * 校验时先验签名、再查 Redis 里还在不在，两个都过才认。
 *
 * @author mybotweb
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    /** 载荷里"管理员 id"这一项的键名 */
    public static final String CLAIM_UID = "uid";

    /** 载荷里"显示名"这一项的键名 */
    public static final String CLAIM_NICKNAME = "nickname";

    /**
     * 载荷里"这份令牌代表哪种身份"的键名。
     *
     * <p>═══════════════════════════════════════════════════════
     * <p><b>为什么必须有这一项</b>
     *
     * <p>管理员和普通用户用的是<b>同一个签名密钥</b>（同一个应用，
     * 没必要维护两套密钥）。如果没有类型标记，两份令牌在密码学上
     * 完全等价 —— 只靠载荷里有没有 {@code uid} 之类去猜身份，
     * 很容易在某处判断漏了，把普通用户令牌当成管理员令牌放行。
     *
     * <p>显式写上 type，拦截器就能一句话拒绝错类型的令牌：
     * 用户接口只认 {@link #TYPE_USER}，后台接口只认 {@link #TYPE_ADMIN}。
     *
     * <p><b>兼容性</b>：改动之前签发的管理员令牌里没有这一项。
     * {@link #toPrincipal} 不检查 type（它本来就只处理后台），
     * 所以老令牌仍然能用，不会把已登录的会话踢掉。
     */
    public static final String CLAIM_TYPE = "type";

    /** {@link #CLAIM_TYPE} 的取值：管理员 */
    public static final String TYPE_ADMIN = "ADMIN";

    /** {@link #CLAIM_TYPE} 的取值：普通用户 */
    public static final String TYPE_USER = "USER";

    /**
     * HS256 要求的最小密钥长度：256 位 = 32 字节。
     * 短于这个值，jjwt 会直接抛 {@code WeakKeyException}。
     * 提前检查是为了给出一句能看懂的报错，而不是一个库内部的异常类名。
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final BlogProperties blogProperties;

    /** 签名用的密钥，启动时由 {@link #initKey()} 准备好 */
    private Key signingKey;

    /**
     * 准备签名密钥。Bean 构造完就跑，跑在第一次登录请求之前。
     *
     * <p>三种情况分别处理，<b>没有一种是"悄悄用一个默认密钥"</b>：
     * <ol>
     *   <li>配了且够长 → 直接用</li>
     *   <li>配了但太短 → 启动就失败，并说清要多少位。
     *       不这么做的后果是：密钥太弱，令牌可以被暴力破解出来</li>
     *   <li>没配 → 随机生成一个，打警告。这样开发期不用配也能跑，
     *       代价是重启后要重新登录 —— 而正式部署看到警告会去补配置</li>
     * </ol>
     */
    @PostConstruct
    public void initKey() {
        String secret = blogProperties.getJwt().getSecret();

        if (secret == null || secret.isBlank()) {
            secret = generateRandomSecret();
            log.warn("""
                    
                    ================================================================
                    博客的 JWT 密钥没配置（app.blog.jwt.secret），已临时随机生成一个。
                    ----------------------------------------------------------------
                    【后果】应用一重启，所有已登录的后台会话就失效了，需要重新登录。
                    开发期这样没关系；正式部署请在 application-prod.yml 里
                    填一个固定的长随机串（至少 32 个字符），生成一个：
                        openssl rand -base64 48
                    ================================================================
                    """);
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // 这里直接抛，让应用起不来。宁可起不来也不要带着弱密钥跑起来 ——
            // 弱密钥的表现是"一切正常"，等出事时已经晚了
            throw new IllegalStateException(
                    "配置项 app.blog.jwt.secret 太短：当前 " + keyBytes.length
                            + " 字节，HS256 算法要求至少 " + MIN_SECRET_BYTES
                            + " 字节（32 个字符）。请换一个更长的随机串。");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT 签名密钥就绪，令牌有效期 {} 分钟", blogProperties.getJwt().getExpireMinutes());
    }

    /**
     * 给一个管理员签发新令牌。
     *
     * @param user 数据库里查出来的管理员
     * @return 令牌字符串，交给前端保存
     */
    public String createToken(AdminUser user) {
        return createToken(user, UUID.randomUUID().toString());
    }

    /**
     * 同上，但由调用方指定令牌 id。
     *
     * <p><b>为什么要把 id 交出去</b>：签发之后紧接着要往 Redis 登记这条会话
     * （见 {@link TokenRevokeStore#register}），登记用的就是同一个 id。
     * 如果 jti 在这里内部生成，调用方就只能反过来解析一遍令牌才能拿到它 ——
     * 白白多做一次签名校验。
     *
     * @param user    数据库里查出来的管理员
     * @param tokenId 令牌 id，应当是一个随机唯一串
     * @return 令牌字符串
     */
    public String createToken(AdminUser user, String tokenId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + getExpireSeconds() * 1000L);

        return Jwts.builder()
                // sub（subject）是 JWT 标准字段，放"这个令牌代表谁"
                .setSubject(user.getUsername())
                .claim(CLAIM_UID, user.getId())
                .claim(CLAIM_NICKNAME, user.getNickname())
                // 标明身份类型，用户接口和后台接口各自只认自己那一种
                .claim(CLAIM_TYPE, TYPE_ADMIN)
                // jti 是 JWT 标准字段，一个随机唯一标识。
                // 它就是 Redis 里那条记录的主键，登出时靠它定位要删哪一条
                .setId(tokenId)
                .setIssuedAt(now)
                .setExpiration(expiry)
                // signWith 会把三段拼起来并算出签名，这一步用到密钥
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 校验并解析令牌。
     *
     * <p>验的是两件事：<b>签名对不对</b>（有没有被篡改）、
     * <b>过期没有</b>。任何一条不满足都会抛 {@code JwtException}，
     * 调用方接住它转成 401 即可。
     *
     * <p>注意这里<b>不查 Redis</b>：本方法只回答"这张纸条本身是真的吗"，
     * "它有没有被登出作废"是 {@link TokenRevokeStore} 的事。
     * 两件事分开，各自好测，也不会因为 Redis 挂了就完全无法登录。
     *
     * @param token 令牌字符串
     * @return 令牌里的载荷
     * @throws io.jsonwebtoken.JwtException 签名不对、已过期、格式错误时
     */
    public Claims parse(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /**
     * 从载荷还原出当前登录管理员。
     *
     * @param claims 已校验过的载荷
     * @return 登录上下文对象
     */
    public AdminPrincipal toPrincipal(Claims claims) {
        AdminPrincipal principal = new AdminPrincipal();
        // get(键, 类型) 取自定义字段。id 在 JSON 里是数字，
        // 但 JWT 载荷经过反序列化后可能是 Integer，用 Number 接最稳
        Number uid = claims.get(CLAIM_UID, Number.class);
        principal.setId(uid == null ? null : uid.longValue());
        principal.setUsername(claims.getSubject());
        principal.setNickname(claims.get(CLAIM_NICKNAME, String.class));
        principal.setTokenId(claims.getId());
        return principal;
    }

    // ========================================================
    // 普通用户令牌
    //
    // 和管理员令牌共用同一个签名密钥，靠 CLAIM_TYPE 区分。
    // 两段代码刻意不共用方法：共用就得传"这是哪种用户"的参数，
    // 一旦传错，签出来的令牌就是错身份的 —— 分开写，想混用都混不了。
    // ========================================================

    /**
     * 给一个普通用户签发新令牌。
     *
     * @param user    数据库里查出来的用户
     * @param tokenId 令牌 id，由调用方生成（登记 Redis 会话时要用同一个）
     * @return 令牌字符串
     */
    public String createUserToken(AppUser user, String tokenId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + getExpireSeconds() * 1000L);

        return Jwts.builder()
                .setSubject(user.getUsername())
                .claim(CLAIM_UID, user.getId())
                .claim(CLAIM_NICKNAME, user.getNickname())
                .claim(CLAIM_TYPE, TYPE_USER)
                .setId(tokenId)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 从载荷还原出当前登录用户。
     *
     * @param claims 已校验过的载荷
     * @return 用户上下文对象
     */
    public UserPrincipal toUserPrincipal(Claims claims) {
        UserPrincipal principal = new UserPrincipal();
        Number uid = claims.get(CLAIM_UID, Number.class);
        principal.setId(uid == null ? null : uid.longValue());
        principal.setUsername(claims.getSubject());
        principal.setNickname(claims.get(CLAIM_NICKNAME, String.class));
        principal.setTokenId(claims.getId());
        return principal;
    }

    /**
     * 取令牌的身份类型。
     *
     * <p>没有这一项时返回 {@code null} —— 那是改动之前签发的老管理员令牌。
     * 用户拦截器用它把老令牌和非用户令牌一起拒掉：
     * 一个老的后台令牌不应该能调发帖接口。
     *
     * @param claims 已校验过的载荷
     * @return {@code ADMIN} / {@code USER}，或 {@code null}
     */
    public String typeOf(Claims claims) {
        return claims.get(CLAIM_TYPE, String.class);
    }

    /**
     * 令牌有效期，单位秒。
     *
     * <p>Redis 里那条记录也按这个时长设置过期时间 ——
     * 让两边同时失效，省得留下一堆永远过期的垃圾 key。
     *
     * @return 有效秒数
     */
    public long getExpireSeconds() {
        return blogProperties.getJwt().getExpireMinutes() * 60L;
    }

    /**
     * 生成一个随机密钥。
     *
     * <p>{@code SecureRandom} 而不是 {@code Random}：
     * 后者是可预测的伪随机，用它生成密钥等于没加密。
     *
     * <p>再 Base64 编码一次，是为了得到一串纯 ASCII 字符 ——
     * 原始随机字节里可能出现引号、反斜杠之类，写进配置文件会很难处理。
     *
     * @return 44 个字符左右的随机串
     */
    private String generateRandomSecret() {
        byte[] bytes = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
