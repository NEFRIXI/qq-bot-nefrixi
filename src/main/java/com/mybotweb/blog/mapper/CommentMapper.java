package com.mybotweb.blog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mybotweb.blog.entity.Comment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论表的 Mapper。
 *
 * <p>方法全部由 {@code BaseMapper} 提供。用到的查询都是
 * {@code selectList(wrapper)} 配 {@code LambdaQueryWrapper} 拼出来的，
 * 没有需要手写 SQL 的地方。
 *
 * @author mybotweb
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
