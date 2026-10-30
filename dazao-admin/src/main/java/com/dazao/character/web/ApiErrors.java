package com.dazao.character.web;

import com.dazao.character.common.ApiException;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一参数校验与业务错误的 JSON 表达。 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> business(ApiException error) {
        // 流式请求在建立 SSE 前也可能失败，必须明确返回可读取的 JSON 错误。
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst().map(e -> e.getDefaultMessage()).orElse("请求参数无效");
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Map.of("message", message));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, String>> duplicate(DuplicateKeyException error) {
        return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON).body(Map.of("message", "用户名已被使用"));
    }
}
