package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Consultar las reservas propias con dobles: de quién son, en qué orden, paginadas y sin acceso cruzado. */
class ConsultaDeReservasTest {

    private static final Instant T0 = Instant.parse("2026-11-22T15:00:00Z");

    private final StartReservationTest.FakeReservations reservations = new StartReservationTest.FakeReservations();
    private final Map<String, User> usuarios = new HashMap<>();
    private GetReservation consulta;
    private ListReservations listado;
    private int seq;

    private static User usuario(long id, String login, boolean activo) {
        Instant ahora = Instant.parse("2026-01-01T00:00:00Z");
        return new User(id, login, "hash", null, null, null, null, "es", activo, Set.of("ROLE_USER"), ahora, ahora);
    }

    @BeforeEach
    void preparar() {
        usuarios.put("ana", usuario(7, "ana", true));
        usuarios.put("beto", usuario(8, "beto", true));
        usuarios.put("inactivo", usuario(9, "inactivo", false));
        UserRepository users = new UserRepository() {
            public Optional<User> findByLogin(String login) { return Optional.ofNullable(usuarios.get(login)); }
            public boolean existsByLogin(String login) { return usuarios.containsKey(login); }
            public boolean existsByEmail(String email) { return false; }
            public User save(User user) { throw new UnsupportedOperationException(); }
        };
        consulta = new GetReservation(users, reservations);
        listado = new ListReservations(users, reservations);
    }

    /** Una reserva de ese usuario creada {@code segundos} después de T0, en el estado pedido. */
    private Reservation reserva(long userId, long segundos, ReservationStatus estado) {
        seq++;
        Instant creada = T0.plusSeconds(segundos);
        var r = reservations.save(Reservation.start(userId, 218, "Romina", "Acosta",
                new Slot(LocalDate.of(2026, 12, 1).plusDays(seq), LocalTime.of(14, 30), LocalTime.of(15, 0)),
                "Juan", "Pérez", creada));
        if (estado == ReservationStatus.STARTING) {
            return r;
        }
        r = reservations.save(r.held("hold-" + seq, "process-" + seq, creada.plusSeconds(600), creada));
        if (estado == ReservationStatus.HELD) {
            return r;
        }
        r = reservations.save(r.waitingForPhone(creada.plusSeconds(600), creada));
        return switch (estado) {
            case WAITING_FOR_PHONE -> r;
            case CONFIRMED -> reservations.save(r.confirmed(8000 + seq, creada));
            case EXPIRED -> reservations.save(r.expired(creada));
            default -> throw new IllegalArgumentException(estado.name());
        };
    }

    // ---- una reserva ------------------------------------------------------------------------------------------

    @Test
    void elDuenoVeSuReserva() {
        var r = reserva(7, 0, ReservationStatus.WAITING_FOR_PHONE);

        assertThat(consulta.execute("ana", r.id())).isEqualTo(r);
    }

    @Test
    void laReservaDeOtroUsuarioYUnaInexistenteSeRechazanIgual() {
        var deBeto = reserva(8, 0, ReservationStatus.CONFIRMED);

        assertThatThrownBy(() -> consulta.execute("ana", deBeto.id())).isExactlyInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> consulta.execute("ana", 98_765)).isExactlyInstanceOf(ReservationNotFoundException.class);
    }

    @Test
    void unUsuarioInexistenteOInactivoNoConsultaNada() {
        var r = reserva(7, 0, ReservationStatus.CONFIRMED);

        assertThatThrownBy(() -> consulta.execute("fantasma", r.id())).isInstanceOf(AccountUnavailableException.class);
        assertThatThrownBy(() -> consulta.execute("inactivo", r.id())).isInstanceOf(AccountUnavailableException.class);
        assertThatThrownBy(() -> listado.execute("fantasma", null, null, null)).isInstanceOf(AccountUnavailableException.class);
        assertThatThrownBy(() -> listado.execute("inactivo", null, null, null)).isInstanceOf(AccountUnavailableException.class);
    }

    // ---- el listado -------------------------------------------------------------------------------------------

    @Test
    void sinReservasDevuelveUnaPaginaVacia() {
        var pagina = listado.execute("ana", null, null, null);

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.totalItems()).isZero();
        assertThat(pagina.totalPages()).isZero();
        assertThat(pagina.page()).isZero();
        assertThat(pagina.size()).isEqualTo(ListReservations.DEFAULT_SIZE);
    }

    @Test
    void soloVienenLasPropiasYNuncaLasDeOtroUsuario() {
        var a1 = reserva(7, 1, ReservationStatus.CONFIRMED);
        var a2 = reserva(7, 2, ReservationStatus.WAITING_FOR_PHONE);
        var b1 = reserva(8, 3, ReservationStatus.CONFIRMED);

        assertThat(listado.execute("ana", null, null, null).items()).containsExactly(a2, a1).doesNotContain(b1);
        assertThat(listado.execute("beto", null, null, null).items()).containsExactly(b1);
    }

    @Test
    void laMasRecientePrimeroYAIgualFechaElIdMayor() {
        var vieja = reserva(7, 10, ReservationStatus.CONFIRMED);
        var nueva = reserva(7, 30, ReservationStatus.CONFIRMED);
        var mismaFecha1 = reserva(7, 20, ReservationStatus.CONFIRMED);
        var mismaFecha2 = reserva(7, 20, ReservationStatus.CONFIRMED);

        assertThat(listado.execute("ana", null, null, null).items())
                .containsExactly(nueva, mismaFecha2, mismaFecha1, vieja);
    }

    @Test
    void sePuedeFiltrarPorEstado() {
        var confirmada = reserva(7, 1, ReservationStatus.CONFIRMED);
        reserva(7, 2, ReservationStatus.WAITING_FOR_PHONE);
        reserva(7, 3, ReservationStatus.EXPIRED);
        reserva(8, 4, ReservationStatus.CONFIRMED);

        var pagina = listado.execute("ana", ReservationStatus.CONFIRMED, null, null);

        assertThat(pagina.items()).containsExactly(confirmada);
        assertThat(pagina.totalItems()).isEqualTo(1);
    }

    @Test
    void paginaConElTotalYLasPaginasCorrectos() {
        for (int i = 0; i < 5; i++) {
            reserva(7, i, ReservationStatus.CONFIRMED);
        }

        var p0 = listado.execute("ana", null, 0, 2);
        var p1 = listado.execute("ana", null, 1, 2);
        var p2 = listado.execute("ana", null, 2, 2);
        var p3 = listado.execute("ana", null, 3, 2);

        assertThat(p0.items()).hasSize(2);
        assertThat(p1.items()).hasSize(2);
        assertThat(p2.items()).hasSize(1);
        assertThat(p3.items()).as("más allá del final: vacía, pero con el total").isEmpty();
        assertThat(p3.totalItems()).isEqualTo(5);
        assertThat(p0.totalPages()).isEqualTo(3);
        assertThat(java.util.stream.Stream.of(p0, p1, p2).flatMap(p -> p.items().stream()).map(Reservation::id))
                .doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    void unaPaginaOUnTamanoFueraDeRangoSeRechazaNombrandoElParametro() {
        for (int page : new int[]{-1, 1_000_001}) {
            assertThatThrownBy(() -> listado.execute("ana", null, page, 10))
                    .isInstanceOfSatisfying(InvalidPageException.class, e -> assertThat(e.parameter()).isEqualTo("page"));
        }
        for (int size : new int[]{0, -5, ListReservations.MAX_SIZE + 1}) {
            assertThatThrownBy(() -> listado.execute("ana", null, 0, size))
                    .isInstanceOfSatisfying(InvalidPageException.class, e -> assertThat(e.parameter()).isEqualTo("size"));
        }
        assertThat(listado.execute("ana", null, 0, ListReservations.MAX_SIZE).size()).isEqualTo(100);
        assertThat(listado.execute("ana", null, 0, 1).size()).isEqualTo(1);
    }
}
