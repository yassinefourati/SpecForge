package [=pkg];

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
<#if security.usesBasic>
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
</#if>
<#if security.usesJwt>
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
<#if secCfg.keycloakRoles()>
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
</#if>
</#if>
<#-- database-flow controllers carry no @PreAuthorize, so their rules are always URL rules -->
<#assign matchers = secCfg.accessRules != "METHOD" || source.kind == "DATABASE">
<#assign methodSecurity = secCfg.accessRules != "MATCHERS">

/**
 * Spring Security configuration generated from the security schemes of "[=api.title]".
 *
 * Declared schemes:
<#list security.schemes as s>
 *  - [=s.name]: [=s.type]<#if s.type == "APIKEY"> ([=s.paramName] in [=s.in])</#if><#if s.type == "OAUTH2"> (flows: [=s.flows?join(", ")]<#if s.tokenUrl?has_content>, token URL: [=s.tokenUrl]</#if>)</#if><#if s.type == "OPENID_CONNECT"> ([=s.openIdConnectUrl])</#if>
</#list>
 *
<#if security.proxyForward>
 * Proxy mode, forwarded credentials: requests to protected endpoints must carry a credential, which the upstream
 * API validates (scopes included). Nothing is validated locally.
 *
</#if>
 * Access rules style: [=secCfg.accessRules]. Scope authorities use the prefix "[=secCfg.scopeAuthorityPrefix]".
 *
 * [=generatedNote]
 */
@Configuration
@EnableWebSecurity
<#if methodSecurity>
@EnableMethodSecurity
</#if>
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http<#if security.usesApiKey>,
            @Value("${app.security.api-keys:}") String apiKeys</#if><#if security.proxyForward>,
            @Value("${app.upstream.forward-credentials:}") String forwardCredentials</#if>) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
<#if matchers>
                // one statement per rule: a single chained expression overflows javac's stack on large APIs
<#list security.rules as r>
                auth.requestMatchers(HttpMethod.[=r.method], "[=r.pattern?j_string]")<#if r.kind == "PERMIT_ALL">.permitAll()<#elseif r.kind == "AUTHENTICATED">.authenticated()<#else>.access(new WebExpressionAuthorizationManager("[=r.expression?j_string]"))</#if>;
</#list>
                auth.anyRequest().authenticated();
<#else>
                // access is enforced per method with @PreAuthorize (see the controllers)
                auth.anyRequest().permitAll();
</#if>
            });
<#if security.usesBasic>
        http.httpBasic(Customizer.withDefaults());
</#if>
<#if security.usesJwt>
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
</#if>
<#if security.usesApiKey>
        http.addFilterBefore(new ApiKeyAuthFilter(apiKeys), UsernamePasswordAuthenticationFilter.class);
</#if>
<#if security.proxyForward>
        // callers' credentials are validated by the upstream API; here a request only needs to carry one
        http.addFilterBefore(new ForwardedCredentialFilter(forwardCredentials), UsernamePasswordAuthenticationFilter.class);
</#if>
<#if !security.usesBasic && !security.usesJwt>
        http.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
</#if>
        return http.build();
    }
<#if security.usesJwt>

<#if secCfg.keycloakRoles()>
    /**
     * Maps Keycloak realm roles ("realm_access.roles") to ROLE_ authorities, so hasRole("admin") matches the realm
     * role "admin", and the token's scopes to authorities such as [=secCfg.scopeAuthorityPrefix]read.
     */
<#else>
    /** Maps the token's scopes ("scope" / "scp" claims) to authorities such as [=secCfg.scopeAuthorityPrefix]read. */
</#if>
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("[=secCfg.scopeAuthorityPrefix?j_string]");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
<#if secCfg.keycloakRoles()>
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
            Object realm = jwt.getClaim("realm_access");
            if (realm instanceof Map<?, ?> access && access.get("roles") instanceof Collection<?> roles) {
                for (Object role : roles) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                }
            }
            return authorities;
        });
<#else>
        converter.setJwtGrantedAuthoritiesConverter(scopes);
</#if>
        return converter;
    }
</#if>
<#if security.usesBasic>

    /** Development user only. Replace with a real UserDetailsService (database, LDAP, ...). */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder,
            @Value("${app.security.user.name:user}") String name,
            @Value("${app.security.user.password:changeme}") String password) {
        return new InMemoryUserDetailsManager(
            User.withUsername(name).password(encoder.encode(password)).roles("USER").build());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
</#if>
}
