package [=pkg];

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import [=ns].servlet.FilterChain;
import [=ns].servlet.ServletException;
import [=ns].servlet.http.Cookie;
import [=ns].servlet.http.HttpServletRequest;
import [=ns].servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Marks a request as authenticated when it carries one of the credentials that are forwarded to the upstream API
 * (app.upstream.forward-credentials). The credential itself is NOT validated here: the upstream API does that and
 * answers 401/403, which is passed back to the caller. This only rejects anonymous calls to protected endpoints early.
 *
 * [=generatedNote]
 */
public class ForwardedCredentialFilter extends OncePerRequestFilter {

    /** {location, name} pairs, location is header, query or cookie */
    private final List<String[]> credentials = new ArrayList<>();

    public ForwardedCredentialFilter(String forwardCredentials) {
        if (forwardCredentials != null) {
            for (String entry : forwardCredentials.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length == 2 && !parts[1].isBlank()) {
                    credentials.add(new String[] {parts[0].trim(), parts[1].trim()});
                }
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hasCredential(request) && SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("upstream-caller", null, List.of()));
        }
        chain.doFilter(request, response);
    }

    private boolean hasCredential(HttpServletRequest request) {
        for (String[] c : credentials) {
            String value = switch (c[0]) {
                case "header" -> request.getHeader(c[1]);
                case "query" -> request.getParameter(c[1]);
                default -> cookie(request, c[1]);
            };
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
