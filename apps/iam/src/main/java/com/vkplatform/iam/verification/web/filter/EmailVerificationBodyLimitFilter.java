package com.vkplatform.iam.verification.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class EmailVerificationBodyLimitFilter extends OncePerRequestFilter {
    private static final String START_PATH = "/api/v1/email-verifications";
    private static final String VERIFY_SUFFIX = "/verify";
    private static final int START_BODY_LIMIT = 4 * 1024;
    private static final int VERIFY_BODY_LIMIT = 1024;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        int limit = bodyLimit(request);
        if (limit == 0) {
            filterChain.doFilter(request, response);
            return;
        }
        filterChain.doFilter(new LimitedRequest(request, limit), response);
    }

    private static int bodyLimit(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return 0;
        }
        String path = request.getRequestURI();
        if (START_PATH.equals(path)) {
            return START_BODY_LIMIT;
        }
        if (path.startsWith(START_PATH + "/") && path.endsWith(VERIFY_SUFFIX)) {
            return VERIFY_BODY_LIMIT;
        }
        return 0;
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final int limit;

        private LimitedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new LimitedInputStream(super.getInputStream(), limit);
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final int limit;
        private int read;

        private LimitedInputStream(ServletInputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                recordRead(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                recordRead(count);
            }
            return count;
        }

        private void recordRead(int count) throws IOException {
            read += count;
            if (read > limit) {
                throw new IOException("request body exceeds the email-verification limit");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
