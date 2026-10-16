package com.mybotweb.blog.entity;

/**
 * 文章状态常量。
 *
 * <p><b>为什么不用枚举？</b> 用枚举就得再教 MyBatis-Plus 怎么把枚举写进数据库
 * （要么加 {@code @EnumValue}，要么配 {@code IEnum}），多一层记不住的东西。
 * 数据库里这一列本来就是 {@code TINYINT}，用 int 常量最直接。
 *
 * <p><b>为什么不直接写 0 和 1？</b> 代码里出现 {@code status == 1} 时，
 * 三个月后没人记得 1 是发布还是草稿。写成
 * {@code status == ArticleStatus.PUBLISHED} 就不用猜。
 * 这也是项目里 {@code CommandNames} 那套做法的同一个思路：
 * 有含义的值只在一处定义，其他地方引常量。
 *
 * @author mybotweb
 */
public final class ArticleStatus {

    /** 草稿：只有后台能看到，前台列表和详情都查不到 */
    public static final int DRAFT = 0;

    /** 已发布：前台可见 */
    public static final int PUBLISHED = 1;

    /**
     * 私有构造器：这个类只用来放常量，不应该被实例化。
     * 写出来是为了让"它不能 new"这件事在编译期就成立，而不是靠约定。
     */
    private ArticleStatus() {
    }

    /**
     * 判断是不是合法的状态值。
     *
     * <p>用在接收前端传来的 status 参数时 —— 传了 7 进来要拦住，
     * 否则数据库里会出现第三个谁都不认识的状态，前台查不到、
     * 后台列表里显示成空白。
     *
     * @param status 待校验的状态值，可为 {@code null}
     * @return 是 0 或 1 时返回 {@code true}
     */
    public static boolean isValid(Integer status) {
        return status != null && (status == DRAFT || status == PUBLISHED);
    }

    /**
     * 把状态值翻译成给人看的文字，用于日志。
     *
     * @param status 状态值
     * @return "草稿" 或 "已发布"，认不出来时返回"未知(值)"
     */
    public static String describe(Integer status) {
        if (status == null) {
            return "未知(null)";
        }
        return switch (status) {
            case DRAFT -> "草稿";
            case PUBLISHED -> "已发布";
            default -> "未知(" + status + ")";
        };
    }
}
