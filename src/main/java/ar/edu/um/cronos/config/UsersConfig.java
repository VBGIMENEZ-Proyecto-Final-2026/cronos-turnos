package ar.edu.um.cronos.config;

import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.RegisterUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Cableado de los casos de uso de usuarios (la aplicación no conoce a Spring). */
@Configuration(proxyBeanMethods = false)
class UsersConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RegisterUser registerUser(UserRepository users, PasswordHasher hasher, Clock clock) {
        return new RegisterUser(users, hasher, clock);
    }
}
