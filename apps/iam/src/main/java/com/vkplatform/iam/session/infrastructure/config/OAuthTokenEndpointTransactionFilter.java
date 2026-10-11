package com.vkplatform.iam.session.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

final class OAuthTokenEndpointTransactionFilter extends OncePerRequestFilter {
    private final TransactionTemplate transactions;

    OAuthTokenEndpointTransactionFilter(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/oauth2/token".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            transactions.executeWithoutResult(status -> {
                try {
                    filterChain.doFilter(request, response);
                } catch (ServletException | IOException exception) {
                    throw new FilterFailure(exception);
                }
            });
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
