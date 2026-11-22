package ar.edu.um.cronos.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La configuración de Kafka: qué es obligatorio, el grupo propio de cronos y que no se filtra el groupId de la cuenta. */
class CatedraKafkaPropertiesTest {

    private static CatedraKafkaProperties validas() {
        return new CatedraKafkaProperties("kafka.invalid:9092", "alumnos.turnos.acciones.mi-cuenta", null,
                "alumnos-mi-cuenta", null, null, new CatedraKafkaProperties.Retry(3, 3, Duration.ofSeconds(1), 2.0,
                Duration.ofSeconds(30)));
    }

    @Test
    void elGrupoDeCronosEsElDeLaCuentaMasUnSufijoPropioYNoElMismoDeAtlas() {
        assertThat(validas().effectiveGroupId()).isEqualTo("alumnos-mi-cuenta-cronos")
                .isNotEqualTo("alumnos-mi-cuenta");
        assertThat(new CatedraKafkaProperties("k:1", "t", null, "  alumnos-x  ", null, null, null)
                .effectiveGroupId()).as("se quitan los espacios").isEqualTo("alumnos-x-cronos");
    }

    @Test
    void sinServidoresTopicOGrupoNoArrancaYElMensajeDiceCual() {
        assertThatThrownBy(() -> new CatedraKafkaProperties("", "t", null, "g", null, null, null))
                .hasMessageContaining("CATEDRA_KAFKA_BOOTSTRAP_SERVERS");
        assertThatThrownBy(() -> new CatedraKafkaProperties("k:1", "  ", null, "g", null, null, null))
                .hasMessageContaining("CATEDRA_KAFKA_APPOINTMENT_ACTIONS_TOPIC");
        assertThatThrownBy(() -> new CatedraKafkaProperties("k:1", "t", null, null, null, null, null))
                .hasMessageContaining("CATEDRA_KAFKA_CONSUMER_GROUP_ID");
    }

    @Test
    void elTopicDelTelefonoTodaviaEsOpcional() {
        assertThat(validas().phoneTopic()).isNull();
    }

    @Test
    void sinOffsetConfirmadoSeLeeDesdeElPrincipioYSoloSeAdmitenDosValores() {
        assertThat(validas().autoOffsetReset()).isEqualTo("earliest");
        assertThat(new CatedraKafkaProperties("k:1", "t", null, "g", " LATEST ", null, null).autoOffsetReset())
                .isEqualTo("latest");
        assertThatThrownBy(() -> new CatedraKafkaProperties("k:1", "t", null, "g", "none", null, null))
                .hasMessageContaining("earliest o latest");
    }

    @Test
    void losReintentosExigenAlMenosUnIntentoYEsperasCoherentes() {
        assertThatThrownBy(() -> new CatedraKafkaProperties.Retry(0, 3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatedraKafkaProperties.Retry(3, 0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatedraKafkaProperties.Retry(3, 3, Duration.ZERO, 2.0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatedraKafkaProperties.Retry(3, 3, Duration.ofSeconds(10), 2.0, Duration.ofSeconds(1)))
                .as("el tope no puede ser menor que la espera inicial").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elToStringNoEscribeServidoresTopicsNiGrupo() {
        String texto = validas().toString();

        assertThat(texto).doesNotContain("kafka.invalid").doesNotContain("mi-cuenta").doesNotContain("alumnos");
    }
}
