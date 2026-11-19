package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.application.port.out.EmailAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.LoginAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Adaptador del puerto {@link UserRepository} sobre Spring Data JPA. */
@Component
class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository repository;

    JpaUserRepository(SpringDataUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<User> findByLogin(String login) {
        return repository.findByLogin(login).map(UserEntity::toDomain);
    }

    @Override
    public boolean existsByLogin(String login) {
        return repository.existsByLogin(login);
    }

    @Override
    public boolean existsByEmail(String email) {
        return repository.existsByEmail(email);
    }

    /**
     * Inserta el usuario. Si dos registros iguales llegan a la vez y la restricción UNIQUE de la base salta, lo
     * traduce a {@link LoginAlreadyUsedException} o {@link EmailAlreadyUsedException}; cualquier otra violación
     * (un CHECK, por ejemplo) sigue siendo una {@link DataIntegrityViolationException}.
     */
    @Override
    public User save(User user) {
        try {
            return repository.saveAndFlush(UserEntity.from(user)).toDomain();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, "app_user_login_key")) {
                throw new LoginAlreadyUsedException(e);
            }
            if (violates(e, "app_user_email_key")) {
                throw new EmailAlreadyUsedException(e);
            }
            throw e;
        }
    }

    private static boolean violates(Throwable error, String constraint) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
