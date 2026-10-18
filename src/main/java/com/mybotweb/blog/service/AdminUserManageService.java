package com.mybotweb.blog.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mybotweb.blog.auth.TokenRevokeStore;
import com.mybotweb.blog.config.BlogProperties;
import com.mybotweb.blog.entity.AppUser;
import com.mybotweb.blog.entity.AppUserStatus;
import com.mybotweb.blog.mapper.AppUserMapper;
import com.mybotweb.blog.vo.AdminUserListItemVo;
import com.mybotweb.blog.vo.PageVo;
import com.mybotweb.common.exception.BusinessException;
import com.mybotweb.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员对普通用户的管理。
 *
 * <p>目前只有两个动作：查看列表、封禁/解封。
 *
 * <p><b>为什么没有"删除用户"</b>
 * 删掉一个用户之后，他发过的评论和帖子会变成"已注销用户"——
 * 数据还在但没法追溯。而封禁是可逆的：先停用，必要时能恢复，
 * 历史内容也完整保留。真要清理，那是另一个需要慎重设计的动作
 * （要考虑内容怎么办、是否留审计），不该顺手加在这里。
 *
 * @author mybotweb
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserManageService {

    private final AppUserMapper appUserMapper;

    private final TokenRevokeStore tokenRevokeStore;

    private final BlogProperties blogProperties;

    /**
     * 分页查用户。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 分页结果
     */
    public PageVo<AdminUserListItemVo> list(Integer page, Integer size) {
        long current = (page == null || page < 1) ? 1 : page;
        long pageSize = (size == null || size < 1) ? 20 : size;
        // 上限来自配置，防止前端传个 100000 把库和带宽打满
        pageSize = Math.min(pageSize, blogProperties.getPage().getMaxSize());

        Page<AppUser> p = appUserMapper.selectPage(
                new Page<>(current, pageSize),
                new LambdaQueryWrapper<AppUser>()
                        .orderByDesc(AppUser::getCreatedAt));

        List<AdminUserListItemVo> list = p.getRecords().stream()
                .map(AdminUserListItemVo::from)
                .toList();

        return PageVo.of(p, list);
    }

    /**
     * 封禁或解封一个用户。
     *
     * <p>═══════════════════════════════════════════════════════════
     * <p><b>封禁时必须把该用户已有的会话全部注销</b>
     *
     * <p>只改数据库状态是<b>不够的</b>：用户手上的 JWT 还没过期，
     * 后续请求照样能通过签名校验。虽然 {@code UserAuthInterceptor}
     * 每次都会查 Redis 白名单，但那条记录还在 —— 所以要主动删。
     *
     * <p>这也是为什么这里要用 {@code revokeAllOfUser} 而不是只删一条：
     * 用户可能同时在手机和电脑上登录着。
     *
     * @param id     用户 id
     * @param status 新状态：0 正常 / 1 封禁
     * @throws BusinessException 用户不存在、状态值非法
     */
    @Transactional
    public void updateStatus(Long id, Integer status) {
        if (!AppUserStatus.isValid(status)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "状态值只能是 0（正常）或 1（封禁）");
        }

        AppUser user = appUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        Integer oldStatus = user.getStatus();
        if (status.equals(oldStatus)) {
            // 状态没变，什么都不做。避免重复注销会话、重复写日志
            return;
        }

        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        appUserMapper.updateById(user);

        if (status == AppUserStatus.BANNED) {
            // 立刻踢下线。不踢的话，被封的人手上的令牌还能用一个星期
            int kicked = tokenRevokeStore.revokeAllOfUser(user.getUsername());
            log.warn("管理员封禁用户 {}，同时注销了 {} 个会话", user.getUsername(), kicked);
        } else {
            log.info("管理员解封用户 {}", user.getUsername());
        }
    }
}
