package com.vkplatform.iam.verification.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vkplatform.iam.platform.RequestContext;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestControllerAdvice(assignableTypes = EmailVerificationController.class)
public class EmailVerificationExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationExceptionHandler.class);
    private final Clock clock;

    public EmailVerificationExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<PublicError> invalidBody(HttpServletRequest request) {
        return response(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST_BODY",
                "The request body is invalid.",
                false,
                null,
                null
        );
    }

    @ExceptionHandler(VerificationFailure.class)
    ResponseEntity<PublicError> verificationFailure(
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
                LOGGER.warn("Email verification delivery unavailable; request_id={}", requestId(request), failure);
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

    @ExceptionHandler(Exception.class)
    ResponseEntity<PublicError> internal(Exception failure, HttpServletRequest request) {
        LOGGER.error("Email verification request failed; request_id={}", requestId(request), failure);
        return response(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Something went wrong. Try again later or contact support with the request ID.",
                true,
                null,
                null
        );
    }

    private static ResponseEntity<PublicError> response(
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
        return builder.body(new PublicError(code, message, requestId(request), retryable, fields));
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContext.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    record PublicError(
            String code,
            String message,
            @JsonProperty("request_id") String requestId,
            boolean retryable,
            Map<String, List<String>> fields
    ) {
    }
}
