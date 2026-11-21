package ar.edu.um.cronos.config;

import ar.edu.um.cronos.adapters.out.security.NimbusTokenIssuer;
import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.TokenIssuer;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AuthenticateUser;
import ar.edu.um.cronos.application.user.GetAccount;
import ar.edu.um.cronos.application.user.RegisterUser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;

/** Cableado de los casos de uso de usuarios (la aplicación no conoce a Spring). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class UsersConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RegisterUser registerUser(UserRepository users, PasswordHasher hasher, Clock clock) {
        return new RegisterUser(users, hasher, clock);
    }

    /**
     * El par de claves con el que cronos firma y valida sus tokens. Sin clave la aplicación no arranca (falla rápido,
     * como atlas sin la pública). Se carga una sola vez y la pública sale de la privada.
     */
    @Bean
    JwtKeyPair jwtKeyPair(SecurityProperties properties) {
        RSAPrivateKey privateKey = JwtPrivateKeys.load(properties.jwt());
        return new JwtKeyPair(privateKey, JwtPrivateKeys.publicKeyOf(privateKey));
    }

    @Bean
    TokenIssuer tokenIssuer(SecurityProperties properties, JwtKeyPair keys, Clock clock) {
        SecurityProperties.Jwt jwt = properties.jwt();
        return new NimbusTokenIssuer(keys.privateKey(), keys.publicKey(), jwt.ttl(), jwt.rememberMeTtl(), clock);
    }

    @Bean
    GetAccount getAccount(UserRepository users) {
        return new GetAccount(users);
    }

    @Bean
    AuthenticateUser authenticateUser(UserRepository users, PasswordHasher hasher, TokenIssuer tokens) {
        return new AuthenticateUser(users, hasher, tokens);
    }
}
