package com.mybotweb.common.exception;

import com.mybotweb.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常 —— 用来表达"这次请求没成功，但原因是业务上的，不是程序崩了"。
 *
 * <p><b>为什么需要它？</b> 没有这个类的时候，Service 层发现"文章不存在"
 * 只能返回 null 或者 false，然后 Controller 再判断一次该返回什么错误码。
 * 判断散在各处，很容易漏。有了它，Service 可以直接抛出
 * {@code throw new BusinessException(ResultCode.NOT_FOUND, "文章不存在")}，
 * 由 {@link GlobalExceptionHandler} 统一翻译成响应体。
 *
 * <p><b>它和普通 RuntimeException 的区别</b>：
 * <ul>
 *   <li>{@code BusinessException} —— 预料之中的情况，日志按 INFO/WARN 记，
 *       不会打印一大堆堆栈</li>
 *   <li>其他 RuntimeException —— 预料之外的 bug，日志按 ERROR 记并打完整堆栈，
 *       因为那才是需要排查的东西</li>
 * </ul>
 * 这个区分很重要：如果所有异常都打堆栈，日志里全是噪音，真出事时反而找不到。
 *
 * @author mybotweb
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 要返回给前端的业务码，见 {@link ResultCode} */
    private final ResultCode resultCode;

    /**
     * 用枚举里的默认提示语构造异常。
     *
     * @param resultCode 业务码
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /**
     * 用自定义提示语构造异常。最常用的一个 ——
     * 同一个错误码下往往要说清具体是哪里不对。
     *
     * @param resultCode 业务码
     * @param message    给用户看的提示语
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
