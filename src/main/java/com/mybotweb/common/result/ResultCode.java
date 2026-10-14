package com.mybotweb.common.result;

import lombok.Getter;

/**
 * 业务返回码枚举。
 *
 * <p>把"返回码 + 提示语"成对定义在这里，好处是：
 * <ul>
 *   <li>不会出现同一个错误在两处写了不同的码</li>
 *   <li>改文案只改一个地方</li>
 *   <li>需要多语言时，这里换成消息 key 即可</li>
 * </ul>
 *
 * <p>约定：
 * <ul>
 *   <li>{@code 0} 表示成功</li>
 *   <li>{@code 4xx} 表示调用方的问题（参数、权限、找不到）</li>
 *   <li>{@code 5xx} 表示服务端自己的问题</li>
 * </ul>
 * 这套约定跟 HTTP 状态码的思路一致，只是用在响应体里，
 * 这样前端可以统一按 {@code code} 判断，不用去分辨 HTTP 状态。
 *
 * @author mybotweb
 */
@Getter
public enum ResultCode {

    /** 成功 */
    SUCCESS(0, "成功"),

    /** 请求参数不合法，例如内容为空、QQ 号格式不对 */
    PARAM_ERROR(400, "参数错误"),

    /** 没有权限，例如普通用户调用了管理员命令 */
    FORBIDDEN(403, "没有权限执行该操作"),

    /** 请求的资源不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 被限流，例如同一个人发消息太快 */
    TOO_MANY_REQUESTS(429, "操作过于频繁，请稍后再试"),

    /** 服务端内部错误（未预料到的异常） */
    SYSTEM_ERROR(500, "系统内部错误"),

    /** 调用的外部服务出问题，例如大模型超时、腾讯接口失败 */
    EXTERNAL_ERROR(502, "外部服务调用失败"),

    /** 功能尚未启用，例如云端节点还没接通 */
    NOT_IMPLEMENTED(501, "该功能尚未启用");

    /** 返回码 */
    private final Integer code;

    /** 默认提示语 */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
