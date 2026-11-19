package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.TestcontainersConfigurationImport;
import ar.edu.um.cronos.application.port.out.EmailAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.LoginAlreadyUsedException;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Prueba la migración V1 y el adaptador contra un PostgreSQL real (Testcontainers). */
@SpringBootTest
@TestcontainersConfigurationImport
class JpaUserRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    private static User nuevo(String login, String email) {
        return User.register(login, "$2a$10$hashDePrueba", "Juan", "Pérez", email, null, "es", NOW);
    }

    @Test
    void guardaYRecuperaElUsuarioConSusAutoridades() {
        User guardado = users.save(nuevo("juan.perez", "juan@ejemplo.test"));

        assertThat(guardado.id()).isNotNull();
        User leido = users.findByLogin("juan.perez").orElseThrow();
        assertThat(leido.id()).isEqualTo(guardado.id());
        assertThat(leido.email()).isEqualTo("juan@ejemplo.test");
        assertThat(leido.firstName()).isEqualTo("Juan");
        assertThat(leido.langKey()).isEqualTo("es");
        assertThat(leido.activated()).isTrue();
        assertThat(leido.authorities()).containsExactly("ROLE_USER");
        assertThat(leido.createdAt()).isEqualTo(NOW);
        assertThat(leido.passwordHash()).isEqualTo("$2a$10$hashDePrueba");
    }

    @Test
    void sabeSiExisteUnLoginOUnEmail() {
        users.save(nuevo("juan.perez", "juan@ejemplo.test"));

        assertThat(users.existsByLogin("juan.perez")).isTrue();
        assertThat(users.existsByLogin("otro")).isFalse();
        assertThat(users.existsByEmail("juan@ejemplo.test")).isTrue();
        assertThat(users.existsByEmail("otro@ejemplo.test")).isFalse();
        assertThat(users.findByLogin("otro")).isEmpty();
    }

    @Test
    void laBaseRechazaUnLoginRepetido() {
        users.save(nuevo("juan.perez", null));

        assertThatThrownBy(() -> users.save(nuevo("juan.perez", null)))
                .isInstanceOf(LoginAlreadyUsedException.class);
    }

    @Test
    void laBaseRechazaUnEmailRepetido() {
        users.save(nuevo("juan", "mismo@ejemplo.test"));

        assertThatThrownBy(() -> users.save(nuevo("ana", "mismo@ejemplo.test")))
                .isInstanceOf(EmailAlreadyUsedException.class);
    }

    @Test
    void variosUsuariosPuedenNoTenerEmail() {
        users.save(nuevo("juan", null));
        users.save(nuevo("ana", null));

        assertThat(users.existsByLogin("juan")).isTrue();
        assertThat(users.existsByLogin("ana")).isTrue();
    }

    @Test
    void laBaseRechazaUnLoginConMayusculasAunqueElDominioNoLasNormalice() {
        User sinNormalizar = new User(null, "Juan", "hash", null, null, null, null, "es", true,
                java.util.Set.of("ROLE_USER"), NOW, NOW);

        assertThatThrownBy(() -> users.save(sinNormalizar)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laBaseRechazaUnaAutoridadSinElFormatoRole() {
        User raro = new User(null, "juan", "hash", null, null, null, null, "es", true,
                java.util.Set.of("admin"), NOW, NOW);

        assertThatThrownBy(() -> users.save(raro)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laContrasenaNuncaSeGuardaEnClaroEnLaTabla() {
        users.save(nuevo("juan", null));

        String guardado = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE login = 'juan'", String.class);
        assertThat(guardado).startsWith("$2a$").isNotEqualTo("juan");
    }
}
