package com.vkplatform.iam.session.web;

import com.vkplatform.iam.session.infrastructure.config.SessionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserSessionCookieTest {
    @Test
    void expiresTheExactConfiguredSecureHttpOnlyCookie() {
        BrowserSessionCookie cookie = new BrowserSessionCookie(new SessionProperties(
                true,
                "vk-platform-bff",
                "b".repeat(32),
                URI.create("https://web.example.test"),
                Duration.ofDays(30),
                "VK_IAM_SESSION",
                true
        ));
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .isEqualTo("VK_IAM_SESSION=; Path=/; Max-Age=0; Expires=Thu, 1 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax");
    }
}
