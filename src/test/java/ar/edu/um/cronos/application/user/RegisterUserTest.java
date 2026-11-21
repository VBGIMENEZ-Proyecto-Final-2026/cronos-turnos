package ar.edu.um.cronos.application.user;

import ar.edu.um.cronos.application.port.out.EmailAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.LoginAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterUserTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    /** Repositorio en memoria: lo justo para probar el caso de uso sin base ni Spring. */
    static class InMemoryUsers implements UserRepository {
        final List<User> saved = new ArrayList<>();
        boolean raceOnLogin;

        @Override
        public Optional<User> findByLogin(String login) {
            return saved.stream().filter(u -> u.login().equals(login)).findFirst();
        }

        @Override
        public boolean existsByLogin(String login) {
            return !raceOnLogin && findByLogin(login).isPresent();
        }

        @Override
        public boolean existsByEmail(String email) {
            return saved.stream().anyMatch(u -> email.equals(u.email()));
        }

        @Override
        public User save(User user) {
            if (raceOnLogin) {
                throw new LoginAlreadyUsedException();
            }
            User withId = new User((long) saved.size() + 1, user.login(), user.passwordHash(), user.firstName(),
                    user.lastName(), user.email(), user.imageUrl(), user.langKey(), user.activated(),
                    user.authorities(), user.createdAt(), user.updatedAt());
            saved.add(withId);
            return withId;
        }
    }

    private final InMemoryUsers users = new InMemoryUsers();
    private final PasswordHasher hasher = new PasswordHasher() {
        @Override
        public String hash(String rawPassword) {
            return "hash(" + rawPassword.length() + ")";
        }

        @Override
        public boolean matches(String rawPassword, String hash) {
            return hash(rawPassword).equals(hash);
        }
    };
    private final RegisterUser registerUser = new RegisterUser(users, hasher, Clock.fixed(NOW, ZoneOffset.UTC));

    private static RegisterUserCommand command(String login, String email) {
        return new RegisterUserCommand(login, "clave-secreta", "Juan", "Pérez", email, null, null);
    }

    @Test
    void registraUnUsuarioActivoConRoleUserYLaContrasenaHasheada() {
        User user = registerUser.execute(command("Juan.Perez", "Juan@Ejemplo.test"));

        assertThat(user.id()).isEqualTo(1L);
        assertThat(user.login()).isEqualTo("juan.perez");
        assertThat(user.email()).isEqualTo("juan@ejemplo.test");
        assertThat(user.activated()).isTrue();
        assertThat(user.authorities()).containsExactly("ROLE_USER");
        assertThat(user.langKey()).isEqualTo("es");
        assertThat(user.createdAt()).isEqualTo(NOW);
        assertThat(user.passwordHash()).isEqualTo("hash(13)").doesNotContain("clave-secreta");
        assertThat(users.saved).hasSize(1);
    }

    @Test
    void rechazaUnLoginRepetidoAunqueCambienLasMayusculas() {
        registerUser.execute(command("juan", null));

        assertThatThrownBy(() -> registerUser.execute(command("JUAN", null)))
                .isInstanceOf(LoginAlreadyUsedException.class);
        assertThat(users.saved).hasSize(1);
    }

    @Test
    void rechazaUnEmailRepetidoAunqueCambienLasMayusculas() {
        registerUser.execute(command("juan", "juan@ejemplo.test"));

        assertThatThrownBy(() -> registerUser.execute(command("ana", "JUAN@ejemplo.test")))
                .isInstanceOf(EmailAlreadyUsedException.class);
        assertThat(users.saved).hasSize(1);
    }

    @Test
    void dosUsuariosSinEmailNoSeConsideranRepetidos() {
        registerUser.execute(command("juan", null));
        registerUser.execute(command("ana", "  "));

        assertThat(users.saved).hasSize(2);
    }

    @Test
    void siDosRegistrosIgualesCompitenLaExcepcionDelRepositorioSePropaga() {
        users.raceOnLogin = true;

        assertThatThrownBy(() -> registerUser.execute(command("juan", null)))
                .isInstanceOf(LoginAlreadyUsedException.class);
    }

    @Test
    void elComandoNoImprimeLaContrasena() {
        assertThat(command("juan", null).toString()).contains("juan").doesNotContain("clave-secreta");
    }
}
