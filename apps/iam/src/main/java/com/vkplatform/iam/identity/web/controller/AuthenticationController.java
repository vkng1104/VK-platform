package com.vkplatform.iam.identity.web.controller;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticationChallengeStarted;
import com.vkplatform.iam.identity.api.result.AuthenticationGrantIssued;
import com.vkplatform.iam.identity.web.dto.AuthenticationChallengeEnvelope;
import com.vkplatform.iam.identity.web.dto.AuthenticationGrantEnvelope;
import com.vkplatform.iam.identity.web.dto.StartAuthenticationChallengeRequest;
import com.vkplatform.iam.identity.web.dto.VerifyAuthenticationChallengeRequest;
import com.vkplatform.iam.identity.web.mapper.IdentityHttpMapper;
import com.vkplatform.iam.platform.web.RequestContext;
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
@RequestMapping("/api/v1/authentication/challenges")
@ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
public class AuthenticationController {
    private final PasswordlessIdentityOperations operations;
    private final IdentityHttpMapper mapper;

    public AuthenticationController(
            PasswordlessIdentityOperations operations,
            IdentityHttpMapper mapper
    ) {
        this.operations = operations;
        this.mapper = mapper;
    }

    @PostMapping
    ResponseEntity<AuthenticationChallengeEnvelope> start(
            @RequestBody StartAuthenticationChallengeRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthenticationChallengeStarted result = operations.startSignIn(
                mapper.toCommand(request, servletRequest.getRemoteAddr())
        );
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(result));
    }

    @PostMapping("/{id}/verify")
    ResponseEntity<AuthenticationGrantEnvelope> verify(
            @PathVariable String id,
            @RequestBody VerifyAuthenticationChallengeRequest request,
            HttpServletRequest servletRequest
    ) {
        AuthenticationGrantIssued result = operations.verifySignIn(
                mapper.toCommand(id, request, RequestContext.requestId(servletRequest))
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(result));
    }
}
