package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración de Kafka de la cátedra para el consumidor de cronos (resultados de las reservas). Las variables son
 * las mismas que usa atlas ({@code CATEDRA_KAFKA_*}). Los servidores, el topic de acciones y el consumer group son
 * obligatorios cuando el consumidor está encendido ({@code catedra.kafka.consumer.enabled}): si falta alguno, la
 * aplicación no arranca y el mensaje dice cuál.
 *
 * <p><b>El grupo de cronos es propio</b>: la cátedra asigna {@code CATEDRA_KAFKA_CONSUMER_GROUP_ID} y atlas lo usa para el
 * topic del catálogo; cronos consume otro topic con {@code <ese grupo>-cronos}, así los dos servicios no comparten
 * offsets (el sondeo del 2026-11-22 comprobó que la cátedra permite un grupo propio).
 *
 * <p>Los nombres de los topics y del grupo contienen el {@code groupId} de la cuenta, que es un dato personal del alumno:
 * este registro no los escribe en {@code toString} y nada de cronos los escribe en un log.
 */
@ConfigurationProperties("catedra.kafka")
public record CatedraKafkaProperties(
        String bootstrapServers,
        String actionsTopic,
        /** El topic del teléfono (cronos → cátedra). Todavía no se usa: lo lee el productor del paso 5. */
        String phoneTopic,
        String consumerGroupId,
        @DefaultValue("earliest") String autoOffsetReset,
        @DefaultValue("10s") Duration startRetryInterval,
        @DefaultValue Retry retry) {

    /** Lo que se agrega al grupo que asigna la cátedra para obtener el de cronos. */
    public static final String GROUP_SUFFIX = "-cronos";

    public CatedraKafkaProperties {
        if (blank(bootstrapServers)) {
            throw new IllegalArgumentException("Falta CATEDRA_KAFKA_BOOTSTRAP_SERVERS (servidores de Kafka de la cátedra)");
        }
        if (blank(actionsTopic)) {
            throw new IllegalArgumentException(
                    "Falta CATEDRA_KAFKA_APPOINTMENT_ACTIONS_TOPIC (topic de las acciones de turnos)");
        }
        if (blank(consumerGroupId)) {
            throw new IllegalArgumentException("Falta CATEDRA_KAFKA_CONSUMER_GROUP_ID (consumer group de la cuenta)");
        }
        autoOffsetReset = autoOffsetReset == null ? "earliest" : autoOffsetReset.strip().toLowerCase();
        if (!autoOffsetReset.equals("earliest") && !autoOffsetReset.equals("latest")) {
            throw new IllegalArgumentException("CRONOS_KAFKA_AUTO_OFFSET_RESET solo admite earliest o latest");
        }
    }

    /** El grupo con el que cronos consume: el de la cuenta más {@value #GROUP_SUFFIX}. */
    public String effectiveGroupId() {
        return consumerGroupId.strip() + GROUP_SUFFIX;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Qué hacer cuando aplicar un mensaje falla. Con una sola partición un mensaje no se puede saltear sin romper el
     * orden, así que cada tipo de falla tiene su propia política:
     *
     * @param unknownProcessAttempts intentos en total cuando el proceso del evento no existe (puede ser un evento que
     *                               ganó la carrera contra el guardado); al agotarlos se estaciona y se sigue
     * @param processingAttempts     intentos en total cuando el mensaje falla por otra causa que no es de infraestructura
     *                               (un mensaje que nunca se va a poder aplicar); al agotarlos se estaciona y se sigue
     * @param delay                  espera antes del segundo intento
     * @param multiplier             cuánto crece la espera en cada intento
     * @param maxDelay               tope de la espera. Una falla <b>transitoria</b> de infraestructura (la base no
     *                               responde) se reintenta sin límite de intentos con esta espera máxima: no se descarta
     *                               ni se saltea una confirmación por una caída pasajera
     */
    public record Retry(
            @DefaultValue("3") int unknownProcessAttempts,
            @DefaultValue("3") int processingAttempts,
            @DefaultValue("1s") Duration delay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("30s") Duration maxDelay) {

        public Retry {
            if (unknownProcessAttempts < 1 || processingAttempts < 1) {
                throw new IllegalArgumentException("Los intentos de reintento deben ser al menos 1");
            }
            if (delay == null || delay.isNegative() || delay.isZero() || maxDelay == null || maxDelay.compareTo(delay) < 0) {
                throw new IllegalArgumentException("La espera de reintento debe ser positiva y no mayor que su tope");
            }
        }
    }

    /** Sin los servidores, los topics ni el grupo: contienen el {@code groupId} de la cuenta. */
    @Override
    public String toString() {
        return "CatedraKafkaProperties[autoOffsetReset=" + autoOffsetReset + ", startRetryInterval=" + startRetryInterval
                + ", retry=" + retry + "]";
    }
}
