package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.Reservation;

import java.util.List;

/**
 * Una página de las reservas de un usuario, la más reciente primero.
 *
 * @param page número de página, desde 0
 * @param size tamaño pedido de la página
 */
public record ReservationPage(List<Reservation> items, long totalItems, int page, int size) {

    public ReservationPage {
        items = List.copyOf(items);
    }

    public int totalPages() {
        return (int) ((totalItems + size - 1) / size);
    }
}
