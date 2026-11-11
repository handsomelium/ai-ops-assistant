package com.ake.aiops.web;

import com.ake.aiops.service.BusinessRuleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>注意 {@link BusinessRuleException} 返回的是 <b>422 而不是 500</b>：
 * 业务规则拒绝是预期内的正常结果，不该被当成服务器故障去告警。
 *
 * <p>另外要区分两条链路：
 * <ul>
 *   <li>普通 REST 接口抛业务异常 → 走这里，返回 422 JSON</li>
 *   <li>AI 工具调用时抛业务异常 → 走 {@code ObservableToolCallback}，
 *       转成文本喂回给模型，<b>不会</b>到这里</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessRuleException e) {
        log.info("业务规则拒绝：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", "BUSINESS_RULE_VIOLATION", "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR",
                        "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
}
