package com.dazao.character.web;

import org.springframework.http.HttpStatus;

/** 携带可公开展示的业务错误，避免返回模型密钥或原始供应商异常。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
