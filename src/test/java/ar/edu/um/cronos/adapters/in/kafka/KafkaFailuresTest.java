package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué es una falla pasajera de infraestructura (se reintenta sin límite) y qué es un mensaje que falla por sí mismo. */
class KafkaFailuresTest {

    @Test
    void laBaseQueNoRespondeEsTransitoria() {
        assertThat(KafkaFailures.isTransient(new CannotCreateTransactionException("sin conexión"))).isTrue();
        assertThat(KafkaFailures.isTransient(new DataAccessResourceFailureException("se cayó"))).isTrue();
        assertThat(KafkaFailures.isTransient(new QueryTimeoutException("lenta"))).isTrue();
        assertThat(KafkaFailures.isTransient(new CannotAcquireLockException("bloqueo"))).isTrue();
        assertThat(KafkaFailures.isTransient(new SQLTransientConnectionException("pool agotado"))).isTrue();
        assertThat(KafkaFailures.isTransient(new SQLRecoverableException("recuperable"))).isTrue();
    }

    @Test
    void unConflictoConOtroEscritorEsTransitorio() {
        assertThat(KafkaFailures.isTransient(new ReservationConflictException(null))).isTrue();
    }

    @Test
    void lasClasesDeEstadoSqlDeConexionRecursosYApagadoSonTransitorias() {
        for (String estado : new String[]{"08001", "08006", "53300", "57P01"}) {
            assertThat(KafkaFailures.isTransient(new SQLException("x", estado))).as(estado).isTrue();
        }
    }

    @Test
    void sePuedeReconocerLaCausaTransitoriaAunqueVayaEnvuelta() {
        var envuelta = new IllegalStateException("fallo al aplicar", new RuntimeException("otra capa",
                new SQLException("conexión", "08006")));

        assertThat(KafkaFailures.isTransient(envuelta)).isTrue();
    }

    @Test
    void unaRestriccionDeLaBaseOUnErrorDeProgramaNoSonTransitorios() {
        assertThat(KafkaFailures.isTransient(new DataIntegrityViolationException("duplicado"))).isFalse();
        assertThat(KafkaFailures.isTransient(new SQLException("violación de unicidad", "23505"))).isFalse();
        assertThat(KafkaFailures.isTransient(new IllegalStateException("bug"))).isFalse();
        assertThat(KafkaFailures.isTransient(new NullPointerException())).isFalse();
        assertThat(KafkaFailures.isTransient(null)).isFalse();
    }

    @Test
    void sinElEnvoltorioDelContenedorSeVeLaCausaReal() {
        var real = new IllegalStateException("fallo");

        assertThat(KafkaFailures.cause(new ListenerExecutionFailedException("envoltorio", real))).isSameAs(real);
        assertThat(KafkaFailures.cause(real)).isSameAs(real);
    }

    @Test
    void soloSeEscribeElNombreDeLaClaseNuncaElMensaje() {
        var conDatos = new DataIntegrityViolationException("Failing row contains (Juan, Pérez)",
                new SQLException("detalle con Juan Pérez", "23514"));

        assertThat(KafkaFailures.name(conDatos)).isEqualTo("DataIntegrityViolationException")
                .doesNotContain("Juan");
        assertThat(KafkaFailures.rootName(conDatos)).isEqualTo("SQLException").doesNotContain("Pérez");
        assertThat(KafkaFailures.name(null)).isEqualTo("-");
    }
}
