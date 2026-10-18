package com.mybotweb.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mybotweb.blog.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 普通用户表的 Mapper。
 *
 * <p>方法全部由 {@code BaseMapper} 提供，所以接口是空的。
 *
 * @author mybotweb
 */
@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
