package com.mybotweb.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体 —— 所有 REST 接口都返回这个结构。
 *
 * <p><b>为什么要有它？</b> 如果每个接口各返回各的（有的返回 User、有的返回 List、
 * 有的直接返回字符串），前端就得为每个接口写一套解析逻辑。统一成
 * {@code {code, message, data}} 之后，前端只需要判断一次 {@code code}。
 *
 * <p>JSON 长这样：
 * <pre>{@code
 * {
 *   "code": 0,
 *   "message": "成功",
 *   "data": { "reply": "你好呀" }
 * }
 * }</pre>
 *
 * <p>泛型 {@code <T>} 指的是 {@code data} 字段的类型，
 * 由调用方决定，例如 {@code Result<String>} 或 {@code Result<List<Article>>}。
 *
 * <p>构造方式统一走静态工厂方法（{@code success} / {@code error}），
 * 不直接 {@code new}，这样调用处读起来更像一句话：
 * {@code return Result.success(data);}
 *
 * @param <T> 业务数据的类型
 * @author mybotweb
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务返回码，0 表示成功。取值见 {@link ResultCode} */
    private Integer code;

    /** 提示语，成功时一般是"成功"，失败时说明原因 */
    private String message;

    /** 业务数据。失败时通常为 {@code null} */
    private T data;

    /**
     * 私有构造器：强制调用方使用下面的静态工厂方法，
     * 避免出现"字段没赋全"的对象。
     */
    private Result() {
    }

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ========================================================
    // 成功
    // ========================================================

    /**
     * 成功，不带数据。用于"删除成功"这类只需要知道结果的场景。
     *
     * @param <T> 数据类型
     * @return 成功的响应体，{@code data} 为 {@code null}
     */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 成功，带数据。最常用的一个。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功的响应体
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功，自定义提示语并带数据。
     *
     * @param message 自定义提示语
     * @param data    业务数据
     * @param <T>     数据类型
     * @return 成功的响应体
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    // ========================================================
    // 失败
    // ========================================================

    /**
     * 失败，用枚举里的返回码和默认提示语。
     *
     * @param resultCode 返回码枚举
     * @param <T>        数据类型
     * @return 失败的响应体
     */
    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    /**
     * 失败，用枚举里的返回码，但自定义提示语。
     * 适合"同一个错误码，但这次想说明具体原因"的场景。
     *
     * @param resultCode 返回码枚举
     * @param message    自定义提示语
     * @param <T>        数据类型
     * @return 失败的响应体
     */
    public static <T> Result<T> error(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    /**
     * 失败，完全自定义返回码和提示语。
     *
     * @param code    返回码
     * @param message 提示语
     * @param <T>     数据类型
     * @return 失败的响应体
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 判断本次响应是否成功。省略码值的魔法数字，读起来更清楚。
     *
     * @return 成功返回 {@code true}
     */
    public boolean isSuccess() {
        return ResultCode.SUCCESS.getCode().equals(this.code);
    }
}
