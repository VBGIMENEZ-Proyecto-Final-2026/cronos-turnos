package ar.edu.um.cronos.config;

import ar.edu.um.cronos.adapters.out.rest.CatedraHoldClient;
import ar.edu.um.cronos.application.port.out.CatalogClient;
import ar.edu.um.cronos.application.port.out.HoldGateway;
import ar.edu.um.cronos.application.port.out.PhoneGateway;
import ar.edu.um.cronos.application.port.out.ReservationCancelGateway;
import ar.edu.um.cronos.application.port.out.ProcessedEvents;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.TransactionRunner;
import ar.edu.um.cronos.application.port.out.UnmatchedEvents;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.reservation.CancelReservation;
import ar.edu.um.cronos.application.reservation.GetReservation;
import ar.edu.um.cronos.application.reservation.ListReservations;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent;
import ar.edu.um.cronos.application.reservation.StartReservation;
import ar.edu.um.cronos.application.reservation.SubmitPhone;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.time.Clock;

/**
 * Cablea las reservas: el cliente de holds de la cátedra (con el JWT técnico) y los casos de uso (iniciar una reserva y
 * aplicar lo que la cátedra informa por Kafka). Comparte la
 * configuración de {@link CatedraProperties} con la disponibilidad.
 */
@Configuration(proxyBeanMethods = false)
class ReservationConfig {

    @Bean
    CatedraHoldClient holdGateway(CatedraProperties catedra) {
        // Sin RetryTemplate: crear y confirmar un hold tienen efectos y no se repiten solos (referencia, 18.4).
        return new CatedraHoldClient(
                HttpClientSupport.restClient(catedra.baseUrl(), catedra.connectTimeout(), catedra.readTimeout())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + catedra.idToken().strip())
                        .build());
    }

    @Bean
    StartReservation startReservation(UserRepository users, CatalogClient catalog, HoldGateway holds,
                                      ReservationRepository reservations, Clock clock,
                                      AvailabilityProperties availability) {
        return new StartReservation(users, catalog, holds, reservations, clock, availability.zone());
    }

    /** El mismo cliente de la cátedra, visto como el puerto de cancelación (comparten URL, token y timeouts). */
    @Bean
    CancelReservation cancelReservation(UserRepository users, ReservationRepository reservations,
                                        ReservationCancelGateway catedra, Clock clock) {
        return new CancelReservation(users, reservations, catedra, clock);
    }

    @Bean
    GetReservation getReservation(UserRepository users, ReservationRepository reservations) {
        return new GetReservation(users, reservations);
    }

    @Bean
    ListReservations listReservations(UserRepository users, ReservationRepository reservations) {
        return new ListReservations(users, reservations);
    }

    @Bean
    SubmitPhone submitPhone(UserRepository users, ReservationRepository reservations, PhoneGateway phones, Clock clock) {
        return new SubmitPhone(users, reservations, phones, clock);
    }

    @Bean
    ProcessReservationEvent processReservationEvent(ReservationRepository reservations, ProcessedEvents processed,
                                                    UnmatchedEvents unmatched, TransactionRunner transactions,
                                                    Clock clock) {
        return new ProcessReservationEvent(reservations, processed, unmatched, transactions, clock);
    }
}
