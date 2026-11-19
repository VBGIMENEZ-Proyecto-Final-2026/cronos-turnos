package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.User;

import java.util.Optional;

/** Persistencia de usuarios. El login y el email se reciben ya normalizados (minúsculas). */
public interface UserRepository {

    Optional<User> findByLogin(String login);

    boolean existsByLogin(String login);

    boolean existsByEmail(String email);

    /** Inserta un usuario nuevo y lo devuelve con su id. */
    User save(User user);
}
