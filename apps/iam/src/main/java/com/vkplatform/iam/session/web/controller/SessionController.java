package com.vkplatform.iam.session.web.controller;

import com.vkplatform.iam.platform.web.RequestContext;
import com.vkplatform.iam.session.api.SessionOperations;
import com.vkplatform.iam.session.api.command.RevokeAllSessions;
import com.vkplatform.iam.session.api.command.RevokeSession;
import com.vkplatform.iam.session.api.result.EstablishedSession;
import com.vkplatform.iam.session.web.dto.ExchangeAuthenticationGrantRequest;
import com.vkplatform.iam.session.web.dto.SessionEnvelope;
import com.vkplatform.iam.session.web.dto.SessionsEnvelope;
import com.vkplatform.iam.session.web.mapper.SessionHttpMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/authentication")
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class SessionController {
    private final SessionOperations operations;
    private final SessionHttpMapper mapper;
    private final SecurityContextRepository securityContexts;
    private final SessionAuthenticationStrategy sessionAuthentication;

    public SessionController(
            SessionOperations operations,
            SessionHttpMapper mapper,
            SecurityContextRepository securityContexts,
            SessionAuthenticationStrategy sessionAuthentication
    ) {
        this.operations = operations;
        this.mapper = mapper;
        this.securityContexts = securityContexts;
        this.sessionAuthentication = sessionAuthentication;
    }

    @PostMapping("/grants/exchange")
    ResponseEntity<SessionEnvelope> exchange(
            @RequestBody ExchangeAuthenticationGrantRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        EstablishedSession established = operations.exchange(mapper.toCommand(
                request,
                RequestContext.requestId(servletRequest)
        ));
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        established.accountId().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
        Map<String, String> details = new HashMap<>();
        details.put("session_id", established.session().id().toString());
        details.put("amr", "email_otp");
        authentication.setDetails(details);
        sessionAuthentication.onAuthentication(authentication, servletRequest, servletResponse);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(established.session()));
    }

    @GetMapping("/session")
    ResponseEntity<SessionEnvelope> current(JwtAuthenticationToken authentication) {
        SessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(operations.session(
                        principal.accountId(),
                        principal.sessionId()
                )));
    }

    @GetMapping("/sessions")
    ResponseEntity<SessionsEnvelope> list(JwtAuthenticationToken authentication) {
        SessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        operations.session(principal.accountId(), principal.sessionId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(operations.sessions(principal.accountId())));
    }

    @DeleteMapping("/sessions/{sessionId}")
    ResponseEntity<Void> revoke(
            @PathVariable UUID sessionId,
            JwtAuthenticationToken authentication,
            HttpServletRequest request
    ) {
        SessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        operations.session(principal.accountId(), principal.sessionId());
        operations.revoke(new RevokeSession(
                principal.accountId(),
                sessionId,
                RequestContext.requestId(request)
        ));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    ResponseEntity<Void> revokeAll(
            JwtAuthenticationToken authentication,
            HttpServletRequest request
    ) {
        SessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        operations.session(principal.accountId(), principal.sessionId());
        operations.revokeAll(new RevokeAllSessions(
                principal.accountId(),
                null,
                RequestContext.requestId(request)
        ));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            JwtAuthenticationToken authentication,
            HttpServletRequest request
    ) {
        SessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        operations.session(principal.accountId(), principal.sessionId());
        operations.logout(new RevokeSession(
                principal.accountId(),
                principal.sessionId(),
                RequestContext.requestId(request)
        ));
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        return ResponseEntity.noContent().build();
    }
}
