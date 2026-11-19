package ar.edu.um.cronos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad de cronos-turnos. Sin sesión ni cookies: el token irá en {@code Authorization: Bearer}, por lo que
 * CSRF no aplica. Por ahora solo {@code GET /actuator/health} es público y todo lo demás se deniega; las rutas
 * de registro y login se habilitan junto con sus casos de uso.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
