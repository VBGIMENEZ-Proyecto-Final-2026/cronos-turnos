package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.PhoneNumber;

import java.time.Instant;

/**
 * Lo que cronos le manda a la cátedra para responder el pedido de teléfono ({@code AdditionalInformationSubmitted},
 * referencia 15.5). El número viaja dentro de un {@link PhoneNumber}, que no se imprime: este registro tampoco lo hace.
 *
 * @param eventId              nuevo en cada envío (la cátedra lo usa para no repetir efectos)
 * @param reservationProcessId el proceso de la cátedra; es también la clave del mensaje
 * @param holdId               el hold del proceso
 * @param requestEventId       el {@code eventId} del {@code AdditionalInformationRequested} que se responde
 * @param phone                el teléfono ya limpio y validado
 * @param occurredAt           cuándo se envía
 */
public record PhoneSubmission(String eventId, String reservationProcessId, String holdId, String requestEventId,
                              PhoneNumber phone, Instant occurredAt) {
}
