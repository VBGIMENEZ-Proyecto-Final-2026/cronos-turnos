package ar.edu.um.cronos.application.user;

import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.TokenIssuer;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticateUserTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    /** Hasher de prueba: el "hash" es la contraseña al revés, y registra cuántas veces se verificó. */
    static class FakeHasher implements PasswordHasher {
        int matchCalls;

        @Override
        public String hash(String rawPassword) {
            return new StringBuilder(rawPassword).reverse().toString();
        }

        @Override
        public boolean matches(String rawPassword, String hash) {
            matchCalls++;
            return hash(rawPassword).equals(hash);
        }
    }

    static class FakeTokens implements TokenIssuer {
        final List<String> issued = new ArrayList<>();
        Boolean lastRememberMe;

        @Override
        public String issue(User user, boolean rememberMe) {
            lastRememberMe = rememberMe;
            issued.add(user.login());
            return "token-de-" + user.login();
        }
    }

    private final FakeHasher hasher = new FakeHasher();
    private final FakeTokens tokens = new FakeTokens();

    private AuthenticateUser useCase(User... existing) {
        UserRepository users = new UserRepository() {
            @Override
            public Optional<User> findByLogin(String login) {
                return List.of(existing).stream().filter(u -> u.login().equals(login)).findFirst();
            }

            @Override
            public boolean existsByLogin(String login) {
                return findByLogin(login).isPresent();
            }

            @Override
            public boolean existsByEmail(String email) {
                return false;
            }

            @Override
            public User save(User user) {
                return user;
            }
        };
        return new AuthenticateUser(users, hasher, tokens);
    }

    private User user(String login, String password, boolean activated) {
        return new User(1L, login, hasher.hash(password), null, null, null, null, "es", activated,
                Set.of("ROLE_USER"), NOW, NOW);
    }

    @Test
    void conCredencialesCorrectasEmiteElTokenDelUsuario() {
        assertThat(useCase(user("juan", "clave1234", true)).execute("juan", "clave1234", false))
                .isEqualTo("token-de-juan");
        assertThat(tokens.lastRememberMe).isFalse();
    }

    @Test
    void elLoginNoDistingueMayusculas() {
        assertThat(useCase(user("juan", "clave1234", true)).execute("  JUAN ", "clave1234", false))
                .isEqualTo("token-de-juan");
    }

    @Test
    void rememberMeLlegaAlEmisorDeTokens() {
        useCase(user("juan", "clave1234", true)).execute("juan", "clave1234", true);

        assertThat(tokens.lastRememberMe).isTrue();
    }

    @Test
    void unaContrasenaEquivocadaSeRechazaSinEmitirToken() {
        assertThatThrownBy(() -> useCase(user("juan", "clave1234", true)).execute("juan", "otra-clave", false))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(tokens.issued).isEmpty();
    }

    @Test
    void unLoginInexistenteSeRechazaConLaMismaExcepcion() {
        assertThatThrownBy(() -> useCase().execute("nadie", "clave1234", false))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(tokens.issued).isEmpty();
    }

    @Test
    void unUsuarioNoActivoNoPuedeIniciarSesionAunqueLaContrasenaSeaCorrecta() {
        assertThatThrownBy(() -> useCase(user("juan", "clave1234", false)).execute("juan", "clave1234", false))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThat(tokens.issued).isEmpty();
    }

    @Test
    void conUnLoginInexistenteIgualSeCalculaUnHashParaNoDelatarloPorElTiempo() {
        AuthenticateUser useCase = useCase();
        int antes = hasher.matchCalls;

        assertThatThrownBy(() -> useCase.execute("nadie", "clave1234", false))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(hasher.matchCalls - antes).isEqualTo(1);
    }

    @Test
    void elMensajeDeLaExcepcionNoDicePorQueFallo() {
        assertThat(new InvalidCredentialsException().getMessage()).isEqualTo("Credenciales inválidas");
    }
}
