package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.reservation.StartReservation;
import ar.edu.um.cronos.application.reservation.StartReservationCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    ReservationController(StartReservation startReservation) {
        this.startReservation = startReservation;
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
}
