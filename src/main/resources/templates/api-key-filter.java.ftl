package [=pkg];

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import [=ns].servlet.FilterChain;
import [=ns].servlet.ServletException;
import [=ns].servlet.http.Cookie;
import [=ns].servlet.http.HttpServletRequest;
import [=ns].servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying an API key, as declared by the spec's apiKey security scheme(s):
<#list security.apiKeys as k>
 *  - [=k.name]: "[=k.paramName]" in [=k.in]
</#list>
 * Valid keys come from the property app.security.api-keys (comma separated).
 * This is a starting point: replace {@link #isValid(String)} with a lookup in your key store.
 *
 * [=generatedNote]
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final List<String> validKeys = new ArrayList<>();

    public ApiKeyAuthFilter(String commaSeparatedKeys) {
        if (commaSeparatedKeys != null) {
            for (String key : commaSeparatedKeys.split(",")) {
                if (!key.isBlank()) {
                    validKeys.add(key.trim());
                }
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = extractKey(request);
        if (presented != null && isValid(presented) && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "api-key-client", null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    private String extractKey(HttpServletRequest request) {
        String value;
<#list security.apiKeys as k>
<#if k.in == "header">
        value = request.getHeader("[=k.paramName?j_string]");
<#elseif k.in == "query">
        value = request.getParameter("[=k.paramName?j_string]");
<#else>
        value = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("[=k.paramName?j_string]".equals(cookie.getName())) {
                    value = cookie.getValue();
                }
            }
        }
</#if>
        if (value != null && !value.isBlank()) {
            return value;
        }
</#list>
        return null;
    }

    protected boolean isValid(String presented) {
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (String key : validKeys) {
            ok |= MessageDigest.isEqual(candidate, key.getBytes(StandardCharsets.UTF_8));
        }
        return ok;
    }
}
