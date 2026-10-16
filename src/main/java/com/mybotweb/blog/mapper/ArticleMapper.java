package com.mybotweb.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mybotweb.blog.entity.Article;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章表的 Mapper。
 *
 * <p><b>为什么接口里一个方法都没有</b>
 * 继承 {@code BaseMapper<Article>} 就已经有了 insert、deleteById、updateById、
 * selectById、selectList、selectPage 等十几个方法，参数里带上实体类或
 * {@code QueryWrapper} 就能用，常见的增删改查一行 SQL 都不用写。
 * 所以这个接口现在是空的 —— 空的对，不是漏了。
 *
 * <p><b>{@code @Mapper} 这个注解不能省</b>
 * 项目里没有配 {@code @MapperScan}，MyBatis 会退回到"扫描启动类所在包下
 * 所有带 {@code @Mapper} 的接口"这个默认行为。
 * 少了注解的表现是：编译通过、启动正常，但注入 Mapper 时
 * 报 {@code NoSuchBeanDefinitionException} —— 一个不难查但很浪费时间的错。
 *
 * <p>将来要写复杂 SQL（比如联表统计）时，在本接口里加方法，
 * 用 {@code @Select} 注解或配一个同名的 XML，不影响现有的自动方法。
 *
 * @author mybotweb
 */
@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}
