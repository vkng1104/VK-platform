package com.vkplatform.iam.session.web.controller;

import com.vkplatform.iam.platform.web.RequestContext;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.web.dto.ExchangeAuthenticationGrantRequest;
import com.vkplatform.iam.session.web.dto.SessionEnvelope;
import com.vkplatform.iam.session.web.mapper.AuthenticationSessionHttpMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/authentication/grants")
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class AuthenticationSessionController {
    private final AuthenticationSessionOperations operations;
    private final AuthenticationSessionHttpMapper mapper;
    private final SecurityContextRepository securityContexts;
    private final SessionAuthenticationStrategy sessionAuthentication;

    public AuthenticationSessionController(
            AuthenticationSessionOperations operations,
            AuthenticationSessionHttpMapper mapper,
            SecurityContextRepository securityContexts,
            SessionAuthenticationStrategy sessionAuthentication
    ) {
        this.operations = operations;
        this.mapper = mapper;
        this.securityContexts = securityContexts;
        this.sessionAuthentication = sessionAuthentication;
    }

    @PostMapping("/exchange")
    ResponseEntity<SessionEnvelope> exchange(
            @RequestBody ExchangeAuthenticationGrantRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        EstablishedAuthenticationSession established = operations.exchange(mapper.toCommand(
                request,
                RequestContext.requestId(servletRequest)
        ));
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        established.accountId().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
        authentication.setDetails(Map.of(
                "session_id", established.session().id().toString(),
                "amr", "email_otp"
        ));
        sessionAuthentication.onAuthentication(authentication, servletRequest, servletResponse);
        var context = securityContexts.loadDeferredContext(servletRequest).get();
        context.setAuthentication(authentication);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(established.session()));
    }
}
