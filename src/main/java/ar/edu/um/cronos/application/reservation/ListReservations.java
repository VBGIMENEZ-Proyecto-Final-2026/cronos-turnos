package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.ReservationPage;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.User;

/**
 * Las reservas del usuario, la más reciente primero, con un filtro opcional por estado y paginadas. <b>Solo las propias</b>:
 * el dueño es el del token y no hay ningún parámetro para pedir las de otro. Lee solo la base de cronos.
 */
public class ListReservations {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final UserRepository users;
    private final ReservationRepository reservations;

    public ListReservations(UserRepository users, ReservationRepository reservations) {
        this.users = users;
        this.reservations = reservations;
    }

    /**
     * @param status si no es {@code null}, solo las que están en ese estado
     * @param page   desde 0 (0 si es {@code null})
     * @param size   de 1 a {@value #MAX_SIZE} ({@value #DEFAULT_SIZE} si es {@code null})
     * @throws AccountUnavailableException si el usuario del token ya no existe o no está activo
     * @throws InvalidPageException        si la página o el tamaño están fuera de rango
     */
    public ReservationPage execute(String login, ReservationStatus status, Integer page, Integer size) {
        User user = users.findByLogin(User.normalize(login)).filter(User::activated)
                .orElseThrow(AccountUnavailableException::new);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageNumber < 0 || pageNumber > 1_000_000) {
            throw new InvalidPageException("page");
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw new InvalidPageException("size");
        }
        return reservations.findByUser(user.id(), status, pageNumber, pageSize);
    }
}
