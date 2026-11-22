package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.reservation.StartReservation;
import ar.edu.um.cronos.application.reservation.StartReservationCommand;
import ar.edu.um.cronos.application.reservation.SubmitPhone;
import ar.edu.um.cronos.application.reservation.SubmitPhoneCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    ReservationController(StartReservation startReservation, SubmitPhone submitPhone) {
        this.startReservation = startReservation;
        this.submitPhone = submitPhone;
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
