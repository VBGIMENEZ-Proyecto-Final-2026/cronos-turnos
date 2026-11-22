package ar.edu.um.cronos.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cómo se configura el consumidor del cliente de Kafka: lo que decide no perder ni repetir eventos. */
class CatedraKafkaConfigTest {

    private static Map<String, Object> config(String autoOffsetReset) {
        return CatedraKafkaConfig.consumerConfig(new CatedraKafkaProperties("kafka.invalid:9092",
                "alumnos.turnos.acciones.mi-cuenta", null, "alumnos-mi-cuenta", autoOffsetReset, null,
                new CatedraKafkaProperties.Retry(3, 3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30))));
    }

    @Test
    void consumeConElGrupoPropioDeCronosYNoConElDeAtlas() {
        assertThat(config(null)).containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "alumnos-mi-cuenta-cronos");
    }

    @Test
    void sinOffsetConfirmadoLeeDesdeElPrincipioParaNoPerderNingunEvento() {
        assertThat(config(null)).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(config("latest")).as("se puede cambiar a propósito").containsEntry(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    }

    @Test
    void elOffsetSeConfirmaALaManoYDeAUnMensajePorVez() {
        assertThat(config(null)).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
    }

    @Test
    void nuncaCreaElTopicDeLaCatedra() {
        assertThat(config(null)).containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    }

    @Test
    void unMensajeQueNoSePuedeDeserializarNoTrabaElConsumo() {
        assertThat(config(null)).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        ErrorHandlingDeserializer.class)
                .containsEntry(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                        org.apache.kafka.common.serialization.StringDeserializer.class);
    }

    @Test
    void elClienteSeIdentificaConUnNombreFijoQueNoLlevaElGrupoDeLaCuenta() {
        // El client.id por defecto se arma con el nombre del grupo y aparece en los logs del cliente y en los del broker.
        assertThat(CatedraKafkaConfig.CLIENT_ID).isEqualTo("cronos-eventos-de-reserva")
                .doesNotContain("mi-cuenta").doesNotContain("alumnos");
    }
}
