package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;
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

    @Override
    public User save(User user) {
        return repository.saveAndFlush(UserEntity.from(user)).toDomain();
    }
}
