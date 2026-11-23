package ar.edu.um.cronos.application.port.out;

import java.time.Instant;

/**
 * Cancela una reserva confirmada en la cátedra ({@code POST /api/appointments/{reservationProcessId}/cancel}, referencia
 * 12). <b>La cancelación es idempotente en la cátedra</b>: cancelar una reserva ya cancelada devuelve su estado actual. Eso
 * hace seguro repetirla cuando no se sabe si la primera llegó (referencia 18.4: reconciliar antes de repetir una acción con
 * efectos).
 */
public interface ReservationCancelGateway {

    /**
     * @param reservationId id de la reserva en la cátedra
     * @param cancelledAt   cuándo la cátedra la canceló
     */
    record Cancelled(long reservationId, Instant cancelledAt) {
    }

    /**
     * @param reason el motivo que escribió el usuario (hasta 500 caracteres), o {@code null}. Es texto libre del usuario: se
     *               manda a la cátedra y no se guarda ni se registra
     * @throws CatedraRejectedException    si la cátedra rechazó la operación (no existe, es de otra cuenta, no se puede cancelar)
     * @throws CatedraUnavailableException si no se sabe qué pasó (sin respuesta, timeout, error del servidor o credencial)
     */
    Cancelled cancel(String reservationProcessId, String reason);
}
