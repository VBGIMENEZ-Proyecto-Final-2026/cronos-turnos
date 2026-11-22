package ar.edu.um.cronos.config;

import ar.edu.um.cronos.adapters.in.security.SecurityProblemHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Seguridad de cronos-turnos. Sin sesión ni cookies: el token viaja en {@code Authorization: Bearer}, por lo que
 * CSRF no aplica. Cronos emite los tokens y también los valida, con la clave pública derivada de su privada
 * (RS256).
 *
 * <ul>
 *   <li>Son públicos {@code GET /actuator/health}, {@code POST /api/register} y {@code POST /api/authenticate}.</li>
 *   <li>{@code GET /api/account}, {@code GET /api/professionals/{id}/availability} y
 *       {@code POST /api/reservations} piden {@code ROLE_USER} o {@code ROLE_ADMIN}.</li>
 *   <li>Todo lo demás se deniega, exista o no la ruta. Las demás rutas se habilitan junto con sus casos de uso.</li>
 * </ul>
 *
 * <p>Nada de lo que decide una autorización viene de un identificador enviado por el cliente: sale del token firmado.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    static final String ROLE_USER = "ROLE_USER";
    static final String ROLE_ADMIN = "ROLE_ADMIN";
    /** El claim con las autoridades que escribe {@code NimbusTokenIssuer}. */
    static final String AUTHORITIES_CLAIM = "auth";

    private static final String[] PUBLIC_GET = {"/actuator/health", "/actuator/health/**"};
    private static final String[] PUBLIC_POST = {"/api/register", "/api/authenticate"};

    @Bean
    SecurityProblemHandler securityProblemHandler(JsonMapper mapper) {
        return new SecurityProblemHandler(mapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problems) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/account").hasAnyAuthority(ROLE_USER, ROLE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/professionals/*/availability")
                        .hasAnyAuthority(ROLE_USER, ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/reservations").hasAnyAuthority(ROLE_USER, ROLE_ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/reservations", "/api/reservations/*")
                        .hasAnyAuthority(ROLE_USER, ROLE_ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/reservations/*/phone", "/api/reservations/*/cancel")
                        .hasAnyAuthority(ROLE_USER, ROLE_ADMIN)
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokenResolver())
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter())));
        return http.build();
    }

    /**
     * En las rutas públicas ni se mira el encabezado {@code Authorization}. Sin esto, un cliente que guardó un token
     * vencido y lo manda al iniciar sesión recibiría un {@code 401} de una ruta que no necesita token y no podría
     * entrar nunca más. En el resto de las rutas se lee como siempre.
     */
    private static BearerTokenResolver bearerTokenResolver() {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> publicas = new java.util.ArrayList<>();
        for (String path : PUBLIC_GET) {
            publicas.add(paths.matcher(HttpMethod.GET, path));
        }
        for (String path : PUBLIC_POST) {
            publicas.add(paths.matcher(HttpMethod.POST, path));
        }
        RequestMatcher esPublica = new OrRequestMatcher(publicas);
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> esPublica.matches(request) ? null : delegate.resolve(request);
    }

    /**
     * Firma RS256 con la clave pública (un token firmado con otro algoritmo, como HS256 o {@code none}, se
     * rechaza), vigencia con la tolerancia configurada y {@code sub} y {@code exp} obligatorios: un token sin
     * vencimiento sería válido para siempre.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtKeyPair keys, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(properties.jwt().clockSkew()), requiredClaims()));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> requiredClaims() {
        return jwt -> {
            if (jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getExpiresAt() == null) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Faltan los claims sub o exp", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    /** Las autoridades salen del claim {@code auth} tal cual, sin prefijo: ya traen {@code ROLE_}. */
    private static JwtAuthenticationConverter authenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(AUTHORITIES_CLAIM);
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Sin orígenes configurados no se acepta ninguno (el navegador bloquea las consultas de otros orígenes). Con
     * orígenes, solo {@code GET}, {@code POST} y {@code OPTIONS} y los encabezados que la API usa, sin credenciales.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        SecurityProperties.Cors cors = properties.cors();
        if (!cors.allowedOrigins().isEmpty()) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(cors.allowedOrigins());
            configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-Id"));
            // Para que un cliente web pueda leer el identificador del pedido y citarlo al reportar un error.
            configuration.setExposedHeaders(List.of("X-Request-Id"));
            configuration.setAllowCredentials(false);
            configuration.setMaxAge(cors.maxAge());
            source.registerCorsConfiguration("/**", configuration);
        }
        return source;
    }
}
