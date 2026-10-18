package com.mybotweb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 {@code /music/**} 让给一个<b>外挂目录</b>，这样加歌不用重新打包 jar。
 *
 * <h3>为什么需要这个</h3>
 *
 * <p>{@code static/} 下的文件是被打进 jar 的。音乐有几十 MB，而且是最常变的东西 ——
 * 每加一首就要重新 {@code mvn package} 再传整个 jar 上去，太重了。
 *
 * <p>所以把 {@code /music/**} 这一段单独交给一个 jar 外面的目录管：
 * 换歌、加歌只动那个目录，jar 一个字节都不用碰。
 *
 * <h3>查找顺序（先外挂、后 jar 内）</h3>
 *
 * <ol>
 *   <li>{@code app.static.music-dir} 指向的目录（默认 {@code ./music}）</li>
 *   <li>{@code classpath:/static/music/}（jar 里的，兜底）</li>
 * </ol>
 *
 * <p>外挂在前，意味着你可以用一个外面的同名文件顶掉 jar 里那份。
 *
 * <h3>相对路径按哪个目录算</h3>
 *
 * <p>按<b>进程的工作目录</b>（{@code user.dir}）算，不是按 jar 所在目录。
 * 实际效果：
 * <ul>
 *   <li>IDEA 里跑 → 项目根目录下的 {@code music/}</li>
 *   <li>{@code java -jar} 在哪个目录敲的 → 那个目录下的 {@code music/}</li>
 *   <li>systemd 由 {@code WorkingDirectory} 决定</li>
 * </ul>
 *
 * <p>这个"跟着启动位置走"的行为通常正好是想要的（开发在项目根、部署在 jar 旁边），
 * 但如果你从别的目录启动，就会找不到 —— 那时配成绝对路径即可。
 *
 * @author mybotweb
 */
@Slf4j
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    /** 外挂音乐目录。空字符串 = 不启用，只用 jar 里的。 */
    private final String musicDir;

    public StaticResourceConfig(@Value("${app.static.music-dir:}") String musicDir) {
        this.musicDir = musicDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String external = resolveExternalLocation();

        // 兜底：jar 里那份始终在最后，外挂目录没配或没这个文件时不会 404
        String jarInside = "classpath:/static/music/";

        // 【为什么只有一条 /music/**，不额外给 list.json 开一条】
        //
        // 试过加 registry.addResourceHandler("/music/list.json") 想让它单独不缓存，
        // 结果是 404。原因是 ResourceHttpRequestHandler 对"不以 /** 结尾"的 pattern，
        // 计算"路径里属于本 handler 的那一截"时规则不一样 ——
        // 它会去找 <外挂目录>/music/list.json，而真实文件是 <外挂目录>/list.json。
        //
        // 一条 /** 通吃，语义最确定，也不用去猜框架的路径裁剪规则。
        ResourceHandlerRegistration registration = registry.addResourceHandler("/music/**");

        if (external != null) {
            registration.addResourceLocations(external);
        }

        // 【为什么缓存期是 0】
        //
        // list.json 和 mp3 在同一个路径前缀下，没法只给其中一个设缓存，
        // 而歌单是"改完刷新页面就该看到"的东西 —— 缓存住会让人以为没生效。
        //
        // mp3 不受影响：Spring 对所有静态资源都会回 Last-Modified，
        // 浏览器的第二次请求是 304，实际传输只有几百字节，不会重传那 4MB。
        registration.addResourceLocations(jarInside).setCachePeriod(0);
    }

    /**
     * 把配置的目录变成一个 Spring 能用的 {@code file:} 位置。
     *
     * @return 形如 {@code file:///D:/ds-work/my_web_project/music/} 的字符串；
     *         没配置或目录不可用时返回 {@code null}（表示只用 jar 内的）
     */
    private String resolveExternalLocation() {
        String configured = this.musicDir == null ? "" : this.musicDir.trim();
        if (configured.isEmpty()) {
            log.info("未配置外挂音乐目录（app.static.music-dir 为空），音乐只用 jar 内的 /static/music/");
            return null;
        }

        Path path;
        try {
            path = Paths.get(configured).toAbsolutePath().normalize();
        } catch (Exception e) {
            // 路径本身就不合法（非法字符之类），降级而不是让应用起不来
            log.warn("外挂音乐目录配置无效：{}（{}），音乐改用 jar 内的 /static/music/",
                    configured, e.getMessage());
            return null;
        }

        if (!Files.isDirectory(path)) {
            // 自动建出来。不建的话用户第一次得自己 mkdir，
            // 而且不会有任何提示，只会觉得"我放了文件怎么没用"
            try {
                Files.createDirectories(path);
                log.info("外挂音乐目录原来不存在，已创建：{}", path);
            } catch (IOException e) {
                log.warn("外挂音乐目录 {} 不存在，且创建失败（{}）。音乐将只用 jar 内的 /static/music/",
                        path, e.getMessage());
                return null;
            }
        }

        // 结尾的斜杠不能少：Spring 把 location 当"目录前缀"拼相对路径，
        // 少了斜杠会拼成 .../music歌名.mp3 这种鬼东西
        String location = path.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        log.info("外挂音乐目录已挂载：{} → /music/**（优先于 jar 内同名文件）", path);
        return location;
    }
}
