package dev.tintwym.home_mart_backend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rewrites {@code /mapi/*} to {@code /api/*} so mobile clients can share the same controllers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MapiRewriteFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        String path = uri.startsWith(context) ? uri.substring(context.length()) : uri;

        if (path.equals("/mapi") || path.startsWith("/mapi/")) {
            String rewritten = "/api" + path.substring("/mapi".length());
            HttpServletRequest wrapped = new RewrittenRequest(request, rewritten);
            filterChain.doFilter(wrapped, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static final class RewrittenRequest extends HttpServletRequestWrapper {
        private final String servletPath;

        RewrittenRequest(HttpServletRequest request, String servletPath) {
            super(request);
            this.servletPath = servletPath;
        }

        @Override
        public String getRequestURI() {
            String context = getContextPath() == null ? "" : getContextPath();
            return context + servletPath;
        }

        @Override
        public String getServletPath() {
            return servletPath;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            String scheme = getScheme();
            int port = getServerPort();
            url.append(scheme).append("://").append(getServerName());
            if (("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443)) {
                url.append(':').append(port);
            }
            url.append(getRequestURI());
            return url;
        }
    }
}
