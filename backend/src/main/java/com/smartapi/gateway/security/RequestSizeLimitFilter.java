package com.smartapi.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies larger than {@code security.max-request-bytes} with 413, before anything
 * buffers them. The gateway reads whole bodies into memory to forward them, so without a limit one
 * client could exhaust memory with a single huge upload.
 * <ul>
 *   <li>Content-Length known and too large: rejected immediately, the body is never read;</li>
 *   <li>no Content-Length (chunked): the input stream is wrapped and fails once the limit is passed.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(SecurityProperties properties) {
        this.maxBytes = properties.maxRequestBytes();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            reject(response);
            return;
        }
        try {
            chain.doFilter(request.getContentLengthLong() < 0 ? new LimitedRequest(request, maxBytes) : request,
                    response);
        } catch (PayloadTooLargeException e) {
            if (!response.isCommitted()) {
                reject(response);
            }
        } catch (ServletException | RuntimeException e) {
            if (hasCause(e, PayloadTooLargeException.class) && !response.isCommitted()) {
                reject(response);
            } else {
                throw e;
            }
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload too large\",\"status\":413,"
                + "\"detail\":\"Request body exceeds " + maxBytes + " bytes\"}");
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    static final class PayloadTooLargeException extends IOException {
        PayloadTooLargeException() {
            super("Request body too large");
        }
    }

    /** Counts bytes as they are read and fails past the limit. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream delegate = super.getInputStream();
                stream = new ServletInputStream() {
                    private long count;

                    @Override
                    public int read() throws IOException {
                        int b = delegate.read();
                        if (b >= 0 && ++count > max) {
                            throw new PayloadTooLargeException();
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int n = delegate.read(buffer, offset, length);
                        if (n > 0 && (count += n) > max) {
                            throw new PayloadTooLargeException();
                        }
                        return n;
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
                };
            }
            return stream;
        }
    }
}
