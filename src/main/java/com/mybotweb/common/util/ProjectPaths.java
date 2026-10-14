package com.mybotweb.common.util;

import java.nio.file.Paths;

/**
 * 项目路径工具 —— 生成"给人看的绝对路径"。
 *
 * <p><b>为什么需要它？</b> 提示语里要告诉用户"去改哪个文件"，
 * 最初写的是相对路径 {@code src/main/resources/application-local.yml}。
 * 对熟悉项目结构的人来说没问题，但第一次接手的人根本不知道
 * 那个 {@code src} 在哪儿——这直接导致配置卡住。
 *
 * <p><b>为什么不直接写死 {@code D:\ds-work\my_web_project\...}？</b>
 * 因为换台机器、换个目录、或者部署到 Linux 上就全错了。
 * 所以用 {@link System#getProperty(String)} 取 {@code user.dir}
 * （应用启动时所在的目录，也就是项目根目录），在它基础上拼出来。
 * 这样在哪儿跑，输出的就是哪儿的真实路径。
 *
 * <p>这些方法只用于<b>生成给人看的文本</b>，不参与任何文件读写。
 *
 * @author mybotweb
 */
public final class ProjectPaths {

    /** 工具类，不允许实例化 */
    private ProjectPaths() {
    }

    /**
     * 取项目根目录。
     *
     * <p>注意：这个值取决于"从哪里启动应用"。
     * IDEA 里直接运行 {@code main} 方法时是项目根目录；
     * 用 {@code java -jar} 启动时是 jar 所在的目录。
     * 两种情况下拼出来的配置路径都是对的，因为配置文件本来就相对项目根目录。
     *
     * @return 项目根目录的绝对路径
     */
    public static String projectRoot() {
        return System.getProperty("user.dir", ".");
    }

    /**
     * 取本机配置文件的绝对路径。
     *
     * <p>这个文件是放真实凭证（AppSecret、API Key）和管理员白名单的地方，
     * 也是用户在配置阶段最常需要打开的文件，所以所有提示语里都用
     * 本方法给绝对路径。
     *
     * @return {@code application-local.yml} 的绝对路径
     */
    public static String localConfigFile() {
        return Paths.get(projectRoot(),
                "src", "main", "resources", "application-local.yml").toString();
    }

    /**
     * 取主配置文件的绝对路径。
     *
     * @return {@code application.yml} 的绝对路径
     */
    public static String mainConfigFile() {
        return Paths.get(projectRoot(),
                "src", "main", "resources", "application.yml").toString();
    }
}
