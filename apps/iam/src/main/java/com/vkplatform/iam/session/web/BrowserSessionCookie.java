package com.vkplatform.iam.session.web;

import com.vkplatform.iam.session.infrastructure.config.SessionProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BrowserSessionCookie {
    private final SessionProperties properties;

    public BrowserSessionCookie(SessionProperties properties) {
        this.properties = properties;
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(properties.browserCookieName(), "")
                .httpOnly(true)
                .secure(properties.browserCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
