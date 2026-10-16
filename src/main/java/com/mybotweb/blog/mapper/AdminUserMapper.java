package com.mybotweb.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mybotweb.blog.entity.AdminUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理员表的 Mapper。
 *
 * <p>和 {@link ArticleMapper} 一样，方法都由 {@code BaseMapper} 提供，
 * 所以接口是空的。用到的只有：
 * <ul>
 *   <li>{@code selectCount(null)} —— 判断表是不是空的（决定要不要建初始管理员）</li>
 *   <li>{@code selectOne(wrapper)} —— 按用户名查一个账号</li>
 *   <li>{@code insert(adminUser)} —— 建初始管理员</li>
 * </ul>
 *
 * @author mybotweb
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
