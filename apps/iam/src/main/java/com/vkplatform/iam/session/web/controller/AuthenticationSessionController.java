package com.vkplatform.iam.session.web.controller;

import com.vkplatform.iam.platform.web.RequestContext;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.RevokeAllAuthenticationSessions;
import com.vkplatform.iam.session.api.command.RevokeAuthenticationSession;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.web.dto.ExchangeAuthenticationGrantRequest;
import com.vkplatform.iam.session.web.dto.SessionEnvelope;
import com.vkplatform.iam.session.web.dto.SessionsEnvelope;
import com.vkplatform.iam.session.web.BrowserSessionCookie;
import com.vkplatform.iam.session.web.mapper.AuthenticationSessionHttpMapper;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/authentication")
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class AuthenticationSessionController {
    private final AuthenticationSessionOperations operations;
    private final AuthenticationSessionHttpMapper mapper;
    private final SecurityContextRepository securityContexts;
    private final SessionAuthenticationStrategy sessionAuthentication;
    private final BrowserSessionCookie browserCookie;

    public AuthenticationSessionController(
            AuthenticationSessionOperations operations,
            AuthenticationSessionHttpMapper mapper,
            SecurityContextRepository securityContexts,
            SessionAuthenticationStrategy sessionAuthentication,
            BrowserSessionCookie browserCookie
    ) {
        this.operations = operations;
        this.mapper = mapper;
        this.securityContexts = securityContexts;
        this.sessionAuthentication = sessionAuthentication;
        this.browserCookie = browserCookie;
    }

    @PostMapping("/grants/exchange")
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
        Map<String, String> details = new LinkedHashMap<>();
        details.put("session_id", established.session().id().toString());
        details.put("amr", "email_otp");
        authentication.setDetails(details);
        sessionAuthentication.onAuthentication(authentication, servletRequest, servletResponse);
        var context = securityContexts.loadDeferredContext(servletRequest).get();
        context.setAuthentication(authentication);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(established.session()));
    }

    @GetMapping("/session")
    ResponseEntity<SessionEnvelope> current(JwtAuthenticationToken authentication) {
        AuthenticationSessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(operations.current(
                        principal.accountId(),
                        principal.sessionId()
                )));
    }

    @GetMapping("/sessions")
    ResponseEntity<SessionsEnvelope> sessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            JwtAuthenticationToken authentication
    ) {
        AuthenticationSessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(mapper.toResponse(operations.sessions(
                        principal.accountId(),
                        principal.sessionId(),
                        page,
                        size
                )));
    }

    @DeleteMapping("/sessions/{session_id}")
    ResponseEntity<Void> revoke(
            @PathVariable("session_id") UUID sessionId,
            JwtAuthenticationToken authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthenticationSessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        operations.revoke(new RevokeAuthenticationSession(
                principal.accountId(),
                principal.sessionId(),
                sessionId,
                RequestContext.requestId(request)
        ));
        if (principal.sessionId().equals(sessionId)) {
            terminateBrowserSession(request, response);
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/sessions")
    ResponseEntity<Void> revokeAll(
            JwtAuthenticationToken authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthenticationSessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        operations.revokeAll(new RevokeAllAuthenticationSessions(
                principal.accountId(),
                principal.sessionId(),
                RequestContext.requestId(request)
        ));
        terminateBrowserSession(request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            JwtAuthenticationToken authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        AuthenticationSessionHttpMapper.PrincipalIds principal = mapper.principal(authentication);
        operations.logout(new RevokeAuthenticationSession(
                principal.accountId(),
                principal.sessionId(),
                principal.sessionId(),
                RequestContext.requestId(request)
        ));
        terminateBrowserSession(request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void terminateBrowserSession(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (request.getSession(false) != null) {
            try {
                request.getSession(false).invalidate();
            } catch (IllegalStateException ignored) {
                // A concurrent request may already have invalidated it.
            }
        }
        SecurityContextHolder.clearContext();
        browserCookie.clear(response);
    }
}
