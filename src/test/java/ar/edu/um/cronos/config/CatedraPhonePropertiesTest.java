package ar.edu.um.cronos.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Qué exige el envío del teléfono para arrancar y que no escribe datos de la cuenta. */
class CatedraPhonePropertiesTest {

    @Test
    void sinServidoresTopicOGroupIdNoArrancaYElMensajeDiceCual() {
        assertThatThrownBy(() -> new CatedraPhoneProperties("", "t", "g", null))
                .hasMessageContaining("CATEDRA_KAFKA_BOOTSTRAP_SERVERS");
        assertThatThrownBy(() -> new CatedraPhoneProperties("k:1", " ", "g", null))
                .hasMessageContaining("CATEDRA_KAFKA_APPOINTMENT_PHONE_TOPIC");
        assertThatThrownBy(() -> new CatedraPhoneProperties("k:1", "t", null, null))
                .hasMessageContaining("CATEDRA_GROUP_ID");
    }

    @Test
    void elPlazoDeEnvioTieneUnTopeYUnMinimo() {
        assertThat(new CatedraPhoneProperties("k:1", "t", "g", Duration.ofSeconds(5)).sendTimeout())
                .isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> new CatedraPhoneProperties("k:1", "t", "g", Duration.ofMillis(100)))
                .hasMessageContaining("CRONOS_PHONE_SEND_TIMEOUT");
        assertThatThrownBy(() -> new CatedraPhoneProperties("k:1", "t", "g", Duration.ofMinutes(10)))
                .hasMessageContaining("CRONOS_PHONE_SEND_TIMEOUT");
        assertThatThrownBy(() -> new CatedraPhoneProperties("k:1", "t", "g", null))
                .as("sin valor, es el valor por defecto que pone Spring al enlazar; a mano no puede ser nulo")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elToStringNoEscribeServidoresTopicNiGroupId() {
        String texto = new CatedraPhoneProperties("kafka.invalid:9092", "catedra.turnos.telefono.mi-cuenta",
                "mi-cuenta", Duration.ofSeconds(5)).toString();

        assertThat(texto).doesNotContain("kafka.invalid").doesNotContain("mi-cuenta").doesNotContain("telefono");
    }
}
