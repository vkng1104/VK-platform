package com.vkplatform.iam.identity.web.error;

import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.platform.web.ApiError;
import com.vkplatform.iam.platform.web.RequestContext;
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
public class IdentityExceptionHandler {
    @ExceptionHandler(IdentityFailure.class)
    ResponseEntity<ApiError> identityFailure(IdentityFailure failure, HttpServletRequest request) {
        return switch (failure.reason()) {
            case INVALID_GRANT, ACCOUNT_UNAVAILABLE -> response(
                    request,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "AUTHENTICATION_FAILED",
                    "Authentication could not be completed. Start again and request a new code."
            );
            case INVALID_AUDIENCE, INVALID_ADMIN_CONTEXT -> response(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUTHENTICATION_REQUEST",
                    "The authentication request is invalid."
            );
            case ACCOUNT_NOT_FOUND -> response(
                    request,
                    HttpStatus.NOT_FOUND,
                    "ACCOUNT_NOT_FOUND",
                    "The requested account was not found."
            );
        };
    }

    private static ResponseEntity<ApiError> response(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(
                        code,
                        message,
                        RequestContext.requestId(request),
                        false,
                        null
                ));
    }
}
