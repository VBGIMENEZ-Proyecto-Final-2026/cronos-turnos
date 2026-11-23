package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.reservation.CancelReservation;
import ar.edu.um.cronos.application.reservation.GetReservation;
import ar.edu.um.cronos.application.reservation.ListReservations;
import ar.edu.um.cronos.application.reservation.StartReservation;
import ar.edu.um.cronos.application.reservation.StartReservationCommand;
import ar.edu.um.cronos.application.reservation.SubmitPhone;
import ar.edu.um.cronos.application.reservation.SubmitPhoneCommand;
import ar.edu.um.cronos.domain.ReservationStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las reservas de los usuarios. Requiere un usuario autenticado: el dueño del proceso es el del token, nunca el que
 * diga el cuerpo del pedido.
 */
@RestController
@RequestMapping("/api")
class ReservationController {

    private final StartReservation startReservation;
    private final SubmitPhone submitPhone;
    private final GetReservation getReservation;
    private final ListReservations listReservations;
    private final CancelReservation cancelReservation;

    ReservationController(StartReservation startReservation, SubmitPhone submitPhone, GetReservation getReservation,
                          ListReservations listReservations, CancelReservation cancelReservation) {
        this.cancelReservation = cancelReservation;
        this.startReservation = startReservation;
        this.submitPhone = submitPhone;
        this.getReservation = getReservation;
        this.listReservations = listReservations;
    }

    /**
     * Inicia una reserva: pide el hold, lo confirma inicialmente y deja el proceso esperando la información adicional.
     * Responde {@code 202 Accepted} porque el proceso sigue (el resultado final llega por Kafka). Repetir el mismo
     * pedido no crea otro hold: devuelve el proceso que ya existe.
     */
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReservationResponse start(@Valid @RequestBody ReservationRequest request, @AuthenticationPrincipal Jwt token) {
        return ReservationResponse.from(startReservation.execute(new StartReservationCommand(token.getSubject(),
                request.professionalId(), request.date(), request.startTime(), request.patientFirstName(),
                request.patientLastName(), token.getTokenValue())));
    }

    /**
     * Mis reservas, la más reciente primero. <b>Solo las del usuario del token</b>: no hay ningún parámetro para pedir las de
     * otro. Se pueden filtrar por estado y se paginan ({@code page} desde 0; {@code size} de 1 a 100, 20 por defecto).
     */
    @GetMapping("/reservations")
    ReservationListResponse list(@RequestParam(required = false) ReservationStatus status,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size, @AuthenticationPrincipal Jwt token) {
        return ReservationListResponse.from(listReservations.execute(token.getSubject(), status, page, size));
    }

    /**
     * Una reserva propia y en qué va. Para cualquier otro usuario, y para un id que no existe, es el mismo {@code 404}. Lee solo
     * la base de cronos: el resultado final lo informa la cátedra por Kafka y se ve acá cuando llega.
     */
    @GetMapping("/reservations/{id}")
    ReservationResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt token) {
        return ReservationResponse.from(getReservation.execute(token.getSubject(), id));
    }

    /**
     * Cancela una reserva propia confirmada (requisito 14). El cuerpo es opcional: {@code {"reason": "..."}} con hasta 500
     * caracteres, que se manda a la cátedra y no se guarda ni se registra. Responde {@code 200} con la reserva ya cancelada.
     * Es idempotente: cancelar una reserva ya cancelada la devuelve tal cual. Solo el dueño; para cualquier otro la reserva no
     * existe.
     */
    @PostMapping("/reservations/{id}/cancel")
    ReservationResponse cancel(@PathVariable long id, @RequestBody(required = false) CancelRequest request,
                               @AuthenticationPrincipal Jwt token) {
        return ReservationResponse.from(cancelReservation.execute(token.getSubject(), id,
                request == null ? null : request.reason()));
    }

    /**
     * Responde el pedido de teléfono de la cátedra: valida el formato y manda el número por Kafka. Responde
     * {@code 202 Accepted}: el resultado (confirmada, rechazado el teléfono, vencida) llega después por Kafka y se ve en el
     * estado de la reserva. <b>El teléfono no se guarda ni se registra.</b> Solo el dueño de la reserva puede mandarlo (para
     * cualquier otro, la reserva no existe).
     */
    @PostMapping("/reservations/{id}/phone")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReservationResponse submitPhone(@PathVariable long id, @RequestBody PhoneRequest request,
                                    @AuthenticationPrincipal Jwt token) {
        return ReservationResponse.from(submitPhone.execute(
                new SubmitPhoneCommand(token.getSubject(), id, request.phoneNumber())));
    }
}
