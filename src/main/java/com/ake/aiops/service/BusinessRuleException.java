package com.ake.aiops.service;

/**
 * 业务规则校验失败。
 *
 * <p>注意：这个异常<b>不应该</b>变成 HTTP 500。它会被 AI 工具层捕获、
 * 转成一句人话返回给模型，模型读到之后会自我纠正（换个人、换个参数再试）。
 * 这就是"把业务约束喂回给模型"的标准做法。
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
