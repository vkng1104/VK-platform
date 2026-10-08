package com.vkplatform.iam.verification.web.controller;

import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import com.vkplatform.iam.verification.web.dto.EmailVerificationChallengeEnvelope;
import com.vkplatform.iam.verification.web.dto.StartEmailVerificationRequest;
import com.vkplatform.iam.verification.web.dto.VerifiedEmailEnvelope;
import com.vkplatform.iam.verification.web.dto.VerifyEmailRequest;
import com.vkplatform.iam.verification.web.mapper.EmailVerificationHttpMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/email-verifications")
@ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
public class EmailVerificationController {
    private final EmailVerificationOperations operations;
    private final EmailVerificationHttpMapper mapper;

    public EmailVerificationController(
            EmailVerificationOperations operations,
            EmailVerificationHttpMapper mapper
    ) {
        this.operations = operations;
        this.mapper = mapper;
    }

    @PostMapping
    ResponseEntity<EmailVerificationChallengeEnvelope> start(
            @RequestBody StartEmailVerificationRequest request,
            HttpServletRequest servletRequest
    ) {
        VerificationChallengeStarted result = operations.start(
                mapper.toCommand(request, servletRequest.getRemoteAddr())
        );
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(result));
    }

    @PostMapping("/{id}/verify")
    ResponseEntity<VerifiedEmailEnvelope> verify(
            @PathVariable String id,
            @RequestBody VerifyEmailRequest request
    ) {
        EmailVerified result = operations.verify(mapper.toCommand(id, request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(result));
    }
}
