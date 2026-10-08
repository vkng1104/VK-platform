package com.vkplatform.iam.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidBody(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY",
                "The request body is invalid.",
                false
        );
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception failure, HttpServletRequest request) {
        LOGGER.error("IAM request failed; request_id={}", RequestContext.requestId(request), failure);
        return response(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Something went wrong. Try again later or contact support with the request ID.",
                true
        );
    }

    private static ResponseEntity<ApiError> response(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            boolean retryable
    ) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(
                        code,
                        message,
                        RequestContext.requestId(request),
                        retryable,
                        null
                ));
    }
}
