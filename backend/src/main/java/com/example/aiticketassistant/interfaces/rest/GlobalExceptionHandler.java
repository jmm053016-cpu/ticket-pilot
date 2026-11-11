package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.domain.shared.DomainException;
import com.example.aiticketassistant.domain.shared.NotFoundException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> notFound(NotFoundException ex) {
        return body("NOT_FOUND", ex.getMessage());
    }

    /**
     * 路径不存在 / 没有静态资源 → 404。
     *
     * <p>WebFlux 对未匹配的路径抛 `NoResourceFoundException`，它会被下面的兜底 `Exception` 吃掉变成
     * 500 "Unexpected server error"，还顺手打一条 error 堆栈 —— 明明只是"这个地址不存在"。
     * （2026-11-05 实测：`GET /knowledge/health`、`GET /` 都返回 500。）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> noResourceFound(NoResourceFoundException ex) {
        return body("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler({DomainException.class, IllegalArgumentException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(Exception ex) {
        return body("BAD_REQUEST", ex.getMessage());
    }

    /** 状态冲突（例如"这张申请已经审核过了"）：4xx，不要让前端当成服务端故障。 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleIllegalState(IllegalStateException ex) {
        return body("CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> error(Exception ex) {
        // 兜底 500 必须留下堆栈，否则线上只能看到 "Unexpected server error"。
        log.error("未处理的服务端异常", ex);
        return body("INTERNAL_ERROR", "Unexpected server error");
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("code", code, "message", message, "timestamp", Instant.now());
    }
}
