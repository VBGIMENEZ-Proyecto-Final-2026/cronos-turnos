package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.User;

/**
 * Consultar una reserva propia. Lee solo la base de cronos: no llama a la cátedra, así que responde aunque la cátedra no
 * esté disponible (enunciado 8: seguir atendiendo lo que depende solo de datos propios). El estado es el último que cronos
 * conoce: el resultado final llega por Kafka y puede demorar.
 */
public class GetReservation {

    private final UserRepository users;
    private final ReservationRepository reservations;

    public GetReservation(UserRepository users, ReservationRepository reservations) {
        this.users = users;
        this.reservations = reservations;
    }

    /**
     * @throws AccountUnavailableException  si el usuario del token ya no existe o no está activo
     * @throws ReservationNotFoundException si la reserva no existe <b>o no es del usuario</b>: no se distinguen, para no
     *                                      revelar que existe la de otro
     */
    public Reservation execute(String login, long reservationId) {
        User user = users.findByLogin(User.normalize(login)).filter(User::activated)
                .orElseThrow(AccountUnavailableException::new);
        return reservations.findById(reservationId).filter(r -> r.userId() == user.id())
                .orElseThrow(ReservationNotFoundException::new);
    }
}
