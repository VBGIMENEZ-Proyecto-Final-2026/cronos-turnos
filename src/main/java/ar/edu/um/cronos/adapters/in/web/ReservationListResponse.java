package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.port.out.ReservationPage;

import java.util.List;

/** Una página de las reservas del usuario, la más reciente primero. */
record ReservationListResponse(List<ReservationResponse> items, int page, int size, long totalItems, int totalPages) {

    static ReservationListResponse from(ReservationPage page) {
        return new ReservationListResponse(page.items().stream().map(ReservationResponse::from).toList(), page.page(),
                page.size(), page.totalItems(), page.totalPages());
    }
}
