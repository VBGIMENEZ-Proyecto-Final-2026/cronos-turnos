package ar.edu.um.cronos.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void elAltaDejaAlUsuarioActivoConSoloRoleUser() {
        User user = User.register("juan.perez", "hash", "Juan", "Pérez", "juan@ejemplo.test", null, "es", NOW);

        assertThat(user.id()).isNull();
        assertThat(user.activated()).isTrue();
        assertThat(user.authorities()).containsExactly("ROLE_USER");
        assertThat(user.createdAt()).isEqualTo(NOW);
        assertThat(user.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void loginYEmailSeGuardanEnMinusculasYSinEspacios() {
        User user = User.register("  Juan.PEREZ ", "hash", null, null, " Juan@Ejemplo.TEST ", null, "es", NOW);

        assertThat(user.login()).isEqualTo("juan.perez");
        assertThat(user.email()).isEqualTo("juan@ejemplo.test");
    }

    @Test
    void losCamposOpcionalesVaciosQuedanAusentesYElIdiomaPorDefectoEsEs() {
        User user = User.register("ana", "hash", " ", "", "  ", " ", null, NOW);

        assertThat(user.firstName()).isNull();
        assertThat(user.lastName()).isNull();
        assertThat(user.email()).isNull();
        assertThat(user.imageUrl()).isNull();
        assertThat(user.langKey()).isEqualTo("es");
    }

    @Test
    void conservaElIdiomaInformado() {
        assertThat(User.register("ana", "hash", null, null, null, null, "en", NOW).langKey()).isEqualTo("en");
    }

    @Test
    void toStringNoImprimeElHashDeLaContrasena() {
        User user = User.register("ana", "hash-secreto-123", null, null, null, null, "es", NOW);

        assertThat(user.toString()).contains("ana").doesNotContain("hash-secreto-123");
    }

    @Test
    void lasAutoridadesSonInmutables() {
        User user = User.register("ana", "hash", null, null, null, null, "es", NOW);

        assertThat(user.authorities()).isUnmodifiable();
    }

    @Test
    void exigeLoginYHash() {
        assertThatNullPointerException().isThrownBy(
                () -> new User(null, null, "h", null, null, null, null, "es", true, Set.of(), NOW, NOW));
        assertThatNullPointerException().isThrownBy(
                () -> new User(null, "ana", null, null, null, null, null, "es", true, Set.of(), NOW, NOW));
    }
}
