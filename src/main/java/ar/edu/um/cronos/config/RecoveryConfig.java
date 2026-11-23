package ar.edu.um.cronos.config;

import ar.edu.um.cronos.adapters.in.scheduling.RecoveryScheduler;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.reservation.RecoverReservations;
import ar.edu.um.cronos.application.reservation.StartReservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Cablea la recuperación de las reservas. El caso de uso existe siempre (se puede correr a mano); la repetición solo si
 * {@code cronos.recovery.enabled} es verdadero (por defecto sí).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoveryProperties.class)
class RecoveryConfig {

    @Bean
    RecoverReservations recoverReservations(ReservationRepository reservations, StartReservation starter, Clock clock,
                                            RecoveryProperties properties) {
        return new RecoverReservations(reservations, starter, clock,
                new RecoverReservations.Settings(properties.staleAfter(), properties.expiryGrace(),
                        properties.batchSize()));
    }

    @Bean
    @ConditionalOnProperty(name = "cronos.recovery.enabled", havingValue = "true", matchIfMissing = true)
    RecoveryScheduler recoveryScheduler(RecoverReservations recovery, RecoveryProperties properties) {
        return new RecoveryScheduler(recovery, properties.interval());
    }
}
