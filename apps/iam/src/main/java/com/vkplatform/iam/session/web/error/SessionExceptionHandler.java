package com.vkplatform.iam.session.web.error;

import com.vkplatform.iam.platform.web.ApiError;
import com.vkplatform.iam.platform.web.RequestContext;
import com.vkplatform.iam.session.domain.SessionFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SessionExceptionHandler {
    @ExceptionHandler(SessionFailure.class)
    ResponseEntity<ApiError> sessionFailure(SessionFailure failure, HttpServletRequest request) {
        return switch (failure.reason()) {
            case INVALID_REQUEST -> response(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SESSION_REQUEST",
                    "The session request is invalid.",
                    false
            );
            case INVALID_REFRESH_TOKEN, REFRESH_TOKEN_REUSED, SESSION_UNAVAILABLE -> response(
                    request,
                    HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED",
                    "Authentication is required. Sign in again.",
                    false
            );
            case SESSION_NOT_FOUND -> response(
                    request,
                    HttpStatus.NOT_FOUND,
                    "SESSION_NOT_FOUND",
                    "The requested session was not found.",
                    false
            );
            case TOKEN_ISSUANCE_UNAVAILABLE -> response(
                    request,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TOKEN_ISSUANCE_UNAVAILABLE",
                    "Authentication cannot be completed right now.",
                    true
            );
        };
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
