package com.mybotweb.blog.vo;

import lombok.Data;

import java.util.List;

/**
 * 分页结果 —— 所有"列表类"接口的统一返回结构。
 *
 * <p>形如：
 * <pre>{@code
 * {
 *   "code": 0,
 *   "message": "成功",
 *   "data": {
 *     "records": [ {...}, {...} ],
 *     "total": 37,
 *     "current": 2,
 *     "size": 10,
 *     "pages": 4
 *   }
 * }
 * }</pre>
 *
 * <p><b>为什么不直接把 MyBatis-Plus 的 {@code Page} 对象返回给前端</b>：
 * 那个对象里带着一堆前端用不上的东西（搜索计数开关、优化 join 的标志位等），
 * 而且它的字段名会随库版本变。中间隔一层自己的 VO，
 * 以后换 ORM 或者升级版本，前端一行都不用改。
 *
 * <p>字段名沿用了 MyBatis-Plus 的命名（{@code records} / {@code current}），
 * 不是随手起的名 —— 这样从 {@code Page} 转过来时不用在脑子里做一次翻译，
 * 少一个出错的地方。
 *
 * @param <T> 列表元素的类型
 * @author mybotweb
 */
@Data
public class PageVo<T> {

    /** 当前页的数据 */
    private List<T> records;

    /** 总条数。前端用它算总页数、显示"共 37 篇" */
    private long total;

    /** 当前是第几页，从 1 开始 */
    private long current;

    /** 每页多少条 */
    private long size;

    /** 总页数。后端算好给前端，省得前端自己除（除错了还会多一页空页） */
    private long pages;

    /**
     * 从 MyBatis-Plus 的分页对象转换。
     *
     * <p>{@code pages} 用 MyBatis-Plus 算出来的值。它内部会处理整除的情况
     * （37 条、每页 10 条 → 4 页，最后一页只有 7 条）。
     * 自己没有把握时不要手写这个除法。
     *
     * @param page      MyBatis-Plus 的分页结果
     * @param converted 已经转换好的记录列表
     * @param <T>       转换后元素的类型
     * @return 前端用的分页结构
     */
    public static <T> PageVo<T> of(com.baomidou.mybatisplus.extension.plugins.pagination.Page<?> page,
                                   List<T> converted) {
        PageVo<T> vo = new PageVo<>();
        vo.setRecords(converted);
        vo.setTotal(page.getTotal());
        vo.setCurrent(page.getCurrent());
        vo.setSize(page.getSize());
        vo.setPages(page.getPages());
        return vo;
    }
}
