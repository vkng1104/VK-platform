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
    void commitsCodeConsumptionBeforePublishingTheTokenResponse() throws Exception {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean responseVisibleAtCommit = new AtomicBoolean(true);
        doAnswer(invocation -> {
            responseVisibleAtCommit.set(response.getContentAsByteArray().length > 0);
            return null;
        }).when(transactions).commit(status);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setServletPath("/oauth2/token");

        new OAuthTokenEndpointTransactionFilter(transactions).doFilter(
                request,
                response,
                (current, buffered) -> buffered.getWriter().write("token-response")
        );

        verify(transactions).commit(status);
        assertThat(responseVisibleAtCommit).isFalse();
        assertThat(response.getContentAsString()).isEqualTo("token-response");
    }

    @Test
    void leavesUnrelatedRequestsOutsideTheTransaction() throws Exception {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setServletPath("/oauth2/authorize");
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
