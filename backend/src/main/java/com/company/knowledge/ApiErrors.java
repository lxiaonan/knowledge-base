package com.company.knowledge;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> domain(ApiException e) { return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "输入内容不符合要求，请检查名称、长度和必填项"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> unreadable() { return ResponseEntity.badRequest().body(Map.of("message", "请求内容格式不正确")); }
    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class,org.springframework.web.method.annotation.HandlerMethodValidationException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalidQuery() {return ResponseEntity.badRequest().body(Map.of("message","查询参数无效，请检查页码、每页条数和筛选条件"));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() { return ResponseEntity.status(413).body(Map.of("message", "单个文件不能超过 20 MB")); }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate() { return ResponseEntity.status(409).body(Map.of("message", "该记录已存在")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        log.error("Unexpected request error", e);
        return ResponseEntity.internalServerError().body(Map.of("message", "操作暂时失败，请稍后重试"));
    }
}
