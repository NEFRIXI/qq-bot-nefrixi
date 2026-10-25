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
 * <p>三个动作：查看列表、封禁 / 禁言 / 解封、软删除。
 *
 * <p><b>封禁和禁言的区别</b>
 * 封禁连登录都不行；禁言能登录、能浏览，只是发不了帖也评不了论。
 * 大多数违规是"话说得不对"而不是"这个人不该存在"，
 * 直接封禁会把他的历史内容一起藏起来，处罚和过错不对等。
 *
 * <p><b>为什么"删除"是软删除</b>
 * 直接 {@code DELETE} 掉一行，他发过的文章和评论就成了孤儿 ——
 * {@code article.author_user_id} 和 {@code comment.user_id}
 * 指向一个不存在的 id。页面会显示"已注销用户"，
 * 但真要追溯"这条内容到底是谁发的"、或者手滑删错了要恢复，
 * 数据已经没了。
 *
 * <p>所以删除只往 {@code deleted_at} 写一个时间戳，查询时过滤掉：
 * 表现上这个人消失了，实际上行还在，随时能捞回来。
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
                        // 软删除的用户不出现在列表里，但数据还在库里
                        .isNull(AppUser::getDeletedAt)
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
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "状态值只能是 0（正常）/ 1（封禁）/ 2（禁言）");
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
        } else if (status == AppUserStatus.MUTED) {
            // 禁言不踢线：他还能正常浏览，只是发不出去。
            // 发帖和评论的入口每次都会查一次库，所以下一次动手就会被拦下，
            // 不需要靠"让他重新登录"来生效
            log.warn("管理员禁言用户 {}（保持登录，但发帖和评论都会被拒）", user.getUsername());
        } else {
            log.info("管理员解封用户 {}", user.getUsername());
        }
    }

    /**
     * 软删除一个用户。
     *
     * <p>不执行 {@code DELETE}，只写 {@code deleted_at}：
     * 他发过的文章和评论还在，作者查得到，误删了也能恢复。
     *
     * <p><b>删除必须同时踢掉他所有会话</b>，理由和封禁一样 ——
     * 只从列表里藏起来、令牌还能用，等于自欺欺人。
     *
     * @param id 用户 id
     * @throws BusinessException 用户不存在，或已经删过了
     */
    @Transactional
    public void softDelete(Long id) {
        AppUser user = appUserMapper.selectById(id);
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在或已经被删除");
        }

        LocalDateTime now = LocalDateTime.now();
        user.setDeletedAt(now);
        user.setUpdatedAt(now);
        appUserMapper.updateById(user);

        int kicked = tokenRevokeStore.revokeAllOfUser(user.getUsername());
        log.warn("管理员删除用户 {}（软删除，文章和评论都还在），同时注销了 {} 个会话",
                user.getUsername(), kicked);
    }
}
