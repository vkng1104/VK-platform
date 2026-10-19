package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthTokenEndpointTransactionFilterTest {
    @Test
    void wrapsContextPathTokenRequestsAndCommitsBeforePublishingTheResponse() throws Exception {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean committedBeforeCopy = new AtomicBoolean(true);
        doAnswer(invocation -> {
            committedBeforeCopy.set(response.isCommitted() || response.getContentAsByteArray().length > 0);
            return null;
        }).when(transactions).commit(status);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam/oauth2/token");
        request.setContextPath("/iam");
        request.setServletPath("/oauth2/token");

        new OAuthTokenEndpointTransactionFilter(transactions).doFilter(
                request,
                response,
                (current, buffered) -> {
                    buffered.getWriter().write("token-response");
                    buffered.flushBuffer();
                }
        );

        verify(transactions).commit(status);
        assertThat(committedBeforeCopy).isFalse();
        assertThat(response.getContentAsString()).isEqualTo("token-response");
    }

    @Test
    void doesNotOpenATransactionForUnrelatedRequests() throws Exception {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/authentication/logout");
        request.setServletPath("/api/v1/authentication/logout");
        AtomicBoolean invoked = new AtomicBoolean();

        new OAuthTokenEndpointTransactionFilter(transactions).doFilter(
                request,
                new MockHttpServletResponse(),
                (current, response) -> invoked.set(true)
        );

        assertThat(invoked).isTrue();
        verify(transactions, never()).getTransaction(any());
    }
}
