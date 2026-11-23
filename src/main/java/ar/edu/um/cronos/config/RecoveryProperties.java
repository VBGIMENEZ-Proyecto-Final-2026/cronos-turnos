package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Parámetros de la recuperación y el barrido de vencimiento de las reservas (issue #13).
 *
 * @param enabled     si corre sola (por defecto sí); apagada, ningún proceso se cierra por tiempo
 * @param interval    cada cuánto repite la pasada
 * @param staleAfter  cuánto sin modificarse para que un proceso a medias se considere abandonado: tiene que superar con
 *                    holgura lo que tarda un pedido normal (los timeouts de la cátedra suman unos 13 segundos)
 * @param expiryGrace cuánto se espera después de {@code expiresAt} antes de cerrar un proceso como vencido, para dar
 *                    tiempo a que llegue por Kafka lo que la cátedra haya resuelto justo antes
 * @param batchSize   cuántos procesos por paso y por pasada
 */
@ConfigurationProperties("cronos.recovery")
public record RecoveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("2m") Duration staleAfter,
        @DefaultValue("2m") Duration expiryGrace,
        @DefaultValue("100") int batchSize) {

    public RecoveryProperties {
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("CRONOS_RECOVERY_INTERVAL debe estar entre 1s y 1h");
        }
        if (staleAfter.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalArgumentException(
                    "CRONOS_RECOVERY_STALE_AFTER no puede ser menor a 30s: un pedido normal todavía podría estar en curso");
        }
        if (expiryGrace.isNegative() || expiryGrace.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("CRONOS_RECOVERY_EXPIRY_GRACE debe estar entre 0 y 1h");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("CRONOS_RECOVERY_BATCH_SIZE debe estar entre 1 y 1000");
        }
    }
}
