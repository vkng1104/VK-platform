package com.vkplatform.iam.verification.web.error;

import com.vkplatform.iam.platform.web.ApiError;
import com.vkplatform.iam.platform.web.RequestContext;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class EmailVerificationExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationExceptionHandler.class);
    private final Clock clock;

    public EmailVerificationExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(VerificationFailure.class)
    ResponseEntity<ApiError> verificationFailure(
            VerificationFailure failure,
            HttpServletRequest request
    ) {
        return switch (failure.reason()) {
            case INVALID_EMAIL -> response(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EMAIL",
                    "Enter a valid email address.",
                    false,
                    Map.of("email", List.of("Enter a valid email address.")),
                    null
            );
            case INVALID_PURPOSE -> response(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_VERIFICATION_PURPOSE",
                    "The requested verification purpose is not supported.",
                    false,
                    Map.of("purpose", List.of("Choose a supported verification purpose.")),
                    null
            );
            case INVALID_ID -> response(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_VERIFICATION_ID",
                    "The verification request is invalid. Request a new code and try again.",
                    false,
                    Map.of("id", List.of("Use the verification ID returned when the code was requested.")),
                    null
            );
            case INVALID_CODE_FORMAT -> response(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CODE_FORMAT",
                    "Enter the six-digit verification code.",
                    false,
                    Map.of("code", List.of("Enter exactly six digits.")),
                    null
            );
            case INVALID_OR_EXPIRED_CODE -> response(
                    request,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_OR_EXPIRED_CODE",
                    "The verification code is invalid or expired. Request a new code and try again.",
                    false,
                    null,
                    null
            );
            case RATE_LIMITED -> {
                long retryAfter = Math.max(1, Duration.between(clock.instant(), failure.retryAt()).toSeconds());
                yield response(
                        request,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "EMAIL_VERIFICATION_RATE_LIMITED",
                        "Please wait before requesting another verification code.",
                        true,
                        null,
                        retryAfter
                );
            }
            case DELIVERY_UNAVAILABLE -> {
                LOGGER.warn(
                        "Email verification delivery unavailable; request_id={}",
                        RequestContext.requestId(request),
                        failure
                );
                yield response(
                        request,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "EMAIL_DELIVERY_UNAVAILABLE",
                        "We could not send the verification email. Try again later.",
                        true,
                        null,
                        null
                );
            }
        };
    }

    private static ResponseEntity<ApiError> response(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Map<String, List<String>> fields,
            Long retryAfter
    ) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        }
        return builder.body(new ApiError(
                code,
                message,
                RequestContext.requestId(request),
                retryable,
                fields
        ));
    }
}
