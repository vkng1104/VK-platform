package com.vkplatform.iam.session.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.HttpMethod;

import java.io.IOException;

final class OAuthTokenEndpointTransactionFilter extends OncePerRequestFilter {
    private static final RequestMatcher TOKEN_ENDPOINT =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/oauth2/token");

    private final TransactionTemplate transactions;

    OAuthTokenEndpointTransactionFilter(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !TOKEN_ENDPOINT.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            transactions.executeWithoutResult(status -> {
                try {
                    filterChain.doFilter(request, buffered);
                } catch (ServletException | IOException exception) {
                    throw new FilterFailure(exception);
                }
            });
            buffered.copyBodyToResponse();
        } catch (FilterFailure failure) {
            if (failure.getCause() instanceof ServletException servletException) {
                throw servletException;
            }
            throw (IOException) failure.getCause();
        }
    }

    private static final class FilterFailure extends RuntimeException {
        private FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
