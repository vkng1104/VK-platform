package com.vkplatform.iam.verification.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vkplatform.iam.verification.application.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/email-verifications")
@ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
public class EmailVerificationController {
    private final EmailVerificationService service;

    public EmailVerificationController(EmailVerificationService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ChallengeEnvelope> start(
            @RequestBody StartRequest request,
            HttpServletRequest servletRequest
    ) {
        EmailVerificationService.StartedVerification result = service.start(
                request.email(),
                request.purpose(),
                servletRequest.getRemoteAddr()
        );
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(new ChallengeEnvelope(new ChallengeResponse(
                        result.id(),
                        result.maskedEmail(),
                        result.expiresAt(),
                        result.resendAfter()
                )));
    }

    @PostMapping("/{id}/verify")
    ResponseEntity<VerificationEnvelope> verify(
            @PathVariable String id,
            @RequestBody VerifyRequest request
    ) {
        EmailVerificationService.CompletedVerification result = service.verify(id, request.code());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new VerificationEnvelope(new VerificationResponse(
                        result.id(),
                        result.purpose().wireValue(),
                        result.verifiedAt()
                )));
    }

    record StartRequest(String email, String purpose) {
    }

    record VerifyRequest(String code) {
    }

    record ChallengeEnvelope(ChallengeResponse verification) {
    }

    record VerificationEnvelope(VerificationResponse verification) {
    }

    record ChallengeResponse(
            UUID id,
            @JsonProperty("masked_email") String maskedEmail,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("resend_after") Instant resendAfter
    ) {
    }

    record VerificationResponse(
            UUID id,
            String purpose,
            @JsonProperty("verified_at") Instant verifiedAt
    ) {
    }
}
