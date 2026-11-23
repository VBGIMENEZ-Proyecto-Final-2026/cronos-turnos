package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración del envío del teléfono a la cátedra por Kafka. Son obligatorios cuando el envío está encendido
 * ({@code catedra.phone.enabled}, por defecto sí): {@code CATEDRA_KAFKA_BOOTSTRAP_SERVERS},
 * {@code CATEDRA_KAFKA_APPOINTMENT_PHONE_TOPIC} y {@code CATEDRA_GROUP_ID}. Si falta alguno, la aplicación no arranca y el
 * mensaje dice cuál.
 *
 * <p>El {@code groupId} va en cada mensaje (referencia 15.5) y, como el nombre del topic, contiene datos de la cuenta del
 * alumno: {@code toString} no escribe ninguno de los tres.
 *
 * @param groupId     el identificador técnico de la cuenta ante la cátedra (ya normalizado)
 * @param sendTimeout cuánto se espera la confirmación del broker; pasado ese tiempo el envío falla y no se reintenta solo
 */
@ConfigurationProperties("catedra.phone")
public record CatedraPhoneProperties(
        String bootstrapServers,
        String topic,
        String groupId,
        @DefaultValue("5s") Duration sendTimeout) {

    public CatedraPhoneProperties {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_KAFKA_BOOTSTRAP_SERVERS (servidores de Kafka de la cátedra)");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_KAFKA_APPOINTMENT_PHONE_TOPIC (topic del teléfono)");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_GROUP_ID (identificador de la cuenta ante la cátedra)");
        }
        if (sendTimeout == null || sendTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || sendTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("CRONOS_PHONE_SEND_TIMEOUT debe estar entre 1s y 1m");
        }
    }

    @Override
    public String toString() {
        return "CatedraPhoneProperties[sendTimeout=" + sendTimeout + "]";
    }
}
