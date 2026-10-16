package com.mybotweb.common.exception;

import com.mybotweb.common.result.Result;
import com.mybotweb.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理 —— 把异常翻译成统一的 {@link Result} 响应体。
 *
 * <p><b>没有它会怎样？</b> Service 里抛出的异常会一路冒到 Spring 默认的错误处理，
 * 返回一个 {@code {"timestamp":..., "status":500, "error":"Internal Server Error"}}
 * 格式的响应。前端就得为"正常响应"和"出错响应"写两套解析逻辑，
 * 而且 500 里看不出到底哪里错了。
 *
 * <p><b>为什么 {@code basePackages} 只写博客的包</b>
 * 这个注解加上后，会对<b>匹配范围内的 Controller</b> 生效。
 * 项目里的机器人调试接口（{@code /api/debug/qq}）不在这个范围里，
 * 所以它的异常处理<b>一行都没变</b> —— 这是刻意的：
 * 机器人部分能正常跑就行，不要顺手改它的行为。
 *
 * <p><b>返回的是 HTTP 200 还是 4xx/5xx？</b>
 * 这里一律返回 HTTP 200，真正的状态放在响应体的 {@code code} 里。
 * 这是沿用项目已有的约定，见 {@link ResultCode} 的类注释：
 * 前端只判断一次 {@code code}，不用去分辨 HTTP 状态码。
 *
 * @author mybotweb
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.mybotweb.blog.web")
public class GlobalExceptionHandler {

    /**
     * 业务异常 —— 预料之中的失败，比如"文章不存在"、"slug 重复"。
     *
     * <p>按 WARN 记一行就够了，<b>不打堆栈</b>：
     * 这类情况是对应代码分支主动抛的，堆栈没有任何信息量，
     * 打出来只会把日志撑大、把真正要查的 ERROR 淹掉。
     *
     * @param e 业务异常
     * @return 统一响应体，{@code code} 取自异常里带的业务码
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：code={}，message={}", e.getResultCode().getCode(), e.getMessage());
        return Result.error(e.getResultCode(), e.getMessage());
    }

    /**
     * 参数校验失败 —— {@code @Valid} 校验没过的请求。
     *
     * <p>只把<b>第一个</b>字段的错误信息返回给前端。原因：一次返回七八条
     * "标题不能为空、摘要太长、slug 格式不对"，前端要么全弹出来糊一屏，
     * 要么还得自己挑一条。只给一条，用户改一条再提交，看到下一条 —— 体验更顺。
     *
     * @param e 校验异常
     * @return 统一响应体，提示语形如"标题不能为空"
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        FieldError firstError = e.getBindingResult().getFieldError();
        String message = (firstError == null) ? ResultCode.PARAM_ERROR.getMessage()
                : firstError.getDefaultMessage();
        log.warn("参数校验失败：{}", message);
        return Result.error(ResultCode.PARAM_ERROR, message);
    }

    /**
     * 兜底 —— 上面两个都没接住的异常，说明是没预料到的 bug。
     *
     * <p>这里<b>必须打完整堆栈</b>，而且用户只会看到一句笼统的提示。
     * 两个原因：
     * <ol>
     *   <li>堆栈是排查这类问题唯一的线索，不能省</li>
     *   <li>异常的原始信息可能带 SQL、类名、路径，直接给前端等于泄露内部结构</li>
     * </ol>
     *
     * @param e 任意异常
     * @return 统一响应体，{@code code} 为 500
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception e) {
        log.error("未预料到的异常", e);
        return Result.error(ResultCode.SYSTEM_ERROR);
    }
}
