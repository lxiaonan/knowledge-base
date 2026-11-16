package com.company.knowledge;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "资源不存在或已被删除"); }
    public static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "你没有访问此资源的权限"); }
}
