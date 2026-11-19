package ar.edu.um.cronos.application.user;

import ar.edu.um.cronos.application.port.out.EmailAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.LoginAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;

import java.time.Clock;

/**
 * Registra un usuario final: queda activo en el acto y con {@code ROLE_USER} (enunciado 3.2), con la contraseña
 * guardada como hash. Verifica antes que el login y el email estén libres para dar un error claro; si dos
 * registros iguales llegan a la vez, la restricción de la base lo detecta al guardar y el repositorio lanza la
 * misma excepción.
 */
public class RegisterUser {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final Clock clock;

    public RegisterUser(UserRepository users, PasswordHasher hasher, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.clock = clock;
    }

    public User execute(RegisterUserCommand command) {
        String login = User.normalize(command.login());
        String email = User.normalize(command.email());
        if (users.existsByLogin(login)) {
            throw new LoginAlreadyUsedException();
        }
        if (email != null && users.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        User user = User.register(login, hasher.hash(command.password()), command.firstName(), command.lastName(),
                email, command.imageUrl(), command.langKey(), clock.instant());
        return users.save(user);
    }
}
