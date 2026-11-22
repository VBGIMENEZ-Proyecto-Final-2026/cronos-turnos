package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.ActiveReservationExistsException;
import ar.edu.um.cronos.application.port.out.CatalogClient;
import ar.edu.um.cronos.application.port.out.CatalogUnavailableException;
import ar.edu.um.cronos.application.port.out.CatedraFailure;
import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.HoldGateway;
import ar.edu.um.cronos.application.port.out.ProfessionalNotFoundException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.ProfessionalAgenda;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.User;
import ar.edu.um.cronos.domain.WeeklySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Iniciar una reserva con dobles: el orden de los pasos, qué se guarda antes de cada llamada a la cátedra, qué pasa
 * cuando la cátedra rechaza, cuando no se sabe qué pasó y cuando el mismo pedido llega dos veces.
 */
class StartReservationTest {

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Sábado 21 de noviembre, 12:00 en el consultorio: los turnos del martes 24 son futuros. */
    private static final Clock AHORA = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate MARTES = LocalDate.of(2026, 11, 24);
    private static final LocalTime HORA = LocalTime.of(14, 30);
    private static final Instant VENCE = Instant.parse("2026-11-24T15:00:00Z");
    private static final String TOKEN_DEL_USUARIO = "jwt-del-usuario";

    // ---- dobles ---------------------------------------------------------------------------------------------

    /** Un repositorio en memoria que respeta lo que respeta la base: índice único de activos y versiones. */
    static class FakeReservations implements ReservationRepository {
        final Map<Long, Reservation> rows = new HashMap<>();
        final Map<Long, List<ReservationStatus>> history = new HashMap<>();
        long nextId = 1;
        /** Si no es null, la próxima inserción pierde una carrera contra este proceso. */
        Reservation winsTheRace;

        @Override
        public Reservation save(Reservation r) {
            if (r.id() == null) {
                if (winsTheRace != null) {
                    var rival = winsTheRace;
                    winsTheRace = null;
                    insert(rival);
                    throw new ActiveReservationExistsException(null);
                }
                if (findActive(r.userId(), r.professionalId(), r.date(), r.startTime()).isPresent()) {
                    throw new ActiveReservationExistsException(null);
                }
                return insert(r);
            }
            var current = rows.get(r.id());
            if (current.version() != r.version()) {
                throw new ReservationConflictException(null);
            }
            var saved = withIdAndVersion(r, r.id(), r.version() + 1);
            rows.put(saved.id(), saved);
            history.get(saved.id()).add(saved.status());
            return saved;
        }

        private Reservation insert(Reservation r) {
            var saved = withIdAndVersion(r, nextId++, 0);
            rows.put(saved.id(), saved);
            history.computeIfAbsent(saved.id(), k -> new ArrayList<>()).add(saved.status());
            return saved;
        }

        private static Reservation withIdAndVersion(Reservation r, long id, long version) {
            return new Reservation(id, r.userId(), r.reservationProcessId(), r.holdId(), r.professionalId(),
                    r.professionalFirstName(), r.professionalLastName(), r.date(), r.startTime(), r.endTime(),
                    r.patientFirstName(), r.patientLastName(), r.status(), r.expiresAt(), r.failureCode(),
                    r.requestEventId(), r.lastRejectionReason(), r.catedraReservationId(), r.phoneSubmittedAt(), version,
                    r.createdAt(),
                    r.updatedAt());
        }

        @Override
        public Optional<Reservation> findById(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public ar.edu.um.cronos.application.port.out.ReservationPage findByUser(long userId, ReservationStatus status,
                                                                               int page, int size) {
            var propias = rows.values().stream().filter(r -> r.userId() == userId)
                    .filter(r -> status == null || r.status() == status)
                    .sorted(java.util.Comparator.comparing(Reservation::createdAt).thenComparing(Reservation::id).reversed())
                    .toList();
            var desde = Math.min((long) page * size, propias.size());
            var hasta = Math.min(desde + size, propias.size());
            return new ar.edu.um.cronos.application.port.out.ReservationPage(
                    propias.subList((int) desde, (int) hasta), propias.size(), page, size);
        }

        @Override
        public Optional<Reservation> findByReservationProcessId(String reservationProcessId) {
            return rows.values().stream().filter(r -> reservationProcessId.equals(r.reservationProcessId()))
                    .findFirst();
        }

        @Override
        public Optional<Reservation> findActive(long userId, long professionalId, LocalDate date, LocalTime start) {
            return rows.values().stream().filter(r -> r.userId() == userId && r.professionalId() == professionalId
                    && r.date().equals(date) && r.startTime().equals(start) && r.status().occupiesSlot()).findFirst();
        }

        List<ReservationStatus> historyOf(long id) {
            return history.get(id);
        }
    }

    /** Un gateway que responde lo que se le encola, en orden, y cuenta y recuerda cada llamada. */
    static class ScriptedHolds implements HoldGateway {
        final Deque<Supplier<HoldCreated>> creates = new ArrayDeque<>();
        final Deque<Supplier<HoldConfirmed>> confirms = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<Object[]> createArgs = new ArrayList<>();
        final List<Object[]> confirmArgs = new ArrayList<>();
        /** Para comprobar que se guardó antes de llamar: se consulta en el momento exacto de la llamada. */
        Runnable onCall = () -> { };

        @Override
        public HoldCreated createHold(long professionalId, LocalDate date, LocalTime startTime, String patientId) {
            calls.add("createHold");
            createArgs.add(new Object[]{professionalId, date, startTime, patientId});
            onCall.run();
            return creates.isEmpty() ? new HoldCreated("hold-1", "process-1", VENCE) : creates.poll().get();
        }

        @Override
        public HoldConfirmed confirmHold(String holdId, String patientId, String first, String last) {
            calls.add("confirmHold");
            confirmArgs.add(new Object[]{holdId, patientId, first, last});
            onCall.run();
            return confirms.isEmpty() ? new HoldConfirmed("process-1", VENCE.plusSeconds(60)) : confirms.poll().get();
        }

        int count(String call) {
            return (int) calls.stream().filter(call::equals).count();
        }
    }

    private final FakeReservations reservations = new FakeReservations();
    private final ScriptedHolds holds = new ScriptedHolds();
    private final Map<String, User> usuarios = new HashMap<>();
    private final List<String> tokensAlCatalogo = new ArrayList<>();
    private ProfessionalAgenda agenda;
    private RuntimeException fallaDelCatalogo;
    private StartReservation caso;

    private static User usuario(long id, String login, boolean activo) {
        Instant ahora = Instant.parse("2026-01-01T00:00:00Z");
        return new User(id, login, "hash", null, null, null, null, "es", activo, Set.of("ROLE_USER"), ahora, ahora);
    }

    @BeforeEach
    void preparar() {
        usuarios.put("ana", usuario(7, "ana", true));
        usuarios.put("beto", usuario(8, "beto", true));
        usuarios.put("inactivo", usuario(9, "inactivo", false));
        agenda = new ProfessionalAgenda(218, true, "Romina", "Acosta", List.of(new WeeklySchedule(
                DayOfWeek.TUESDAY, LocalTime.of(13, 0), LocalTime.of(16, 0), 30, true)));
        UserRepository users = new UserRepository() {
            public Optional<User> findByLogin(String login) { return Optional.ofNullable(usuarios.get(login)); }
            public boolean existsByLogin(String login) { return usuarios.containsKey(login); }
            public boolean existsByEmail(String email) { return false; }
            public User save(User user) { throw new UnsupportedOperationException(); }
        };
        CatalogClient catalogo = (id, token) -> {
            tokensAlCatalogo.add(token);
            if (fallaDelCatalogo != null) {
                throw fallaDelCatalogo;
            }
            return agenda;
        };
        caso = new StartReservation(users, catalogo, holds, reservations, AHORA, BUENOS_AIRES);
    }

    private StartReservationCommand pedido(String login) {
        return new StartReservationCommand(login, 218, MARTES, HORA, "  Juan ", " Pérez  ", TOKEN_DEL_USUARIO);
    }

    private static Supplier<HoldGateway.HoldCreated> rechazaHold(CatedraFailure f) {
        return () -> { throw new CatedraRejectedException(f, null); };
    }

    private static <T> Supplier<T> noResponde() {
        return () -> { throw new CatedraUnavailableException("sin respuesta", null); };
    }

    private static Supplier<HoldGateway.HoldConfirmed> rechazaConfirmacion(CatedraFailure f) {
        return () -> { throw new CatedraRejectedException(f, null); };
    }

    // ---- el camino feliz ------------------------------------------------------------------------------------

    @Test
    void iniciaLaReservaYQuedaEsperandoElTelefono() {
        var r = caso.execute(pedido("ana"));

        assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(r.holdId()).isEqualTo("hold-1");
        assertThat(r.reservationProcessId()).isEqualTo("process-1");
        assertThat(r.expiresAt()).isEqualTo(VENCE.plusSeconds(60));
        assertThat(r.userId()).isEqualTo(7);
        assertThat(r.professionalId()).isEqualTo(218);
        assertThat(r.date()).isEqualTo(MARTES);
        assertThat(r.startTime()).isEqualTo(HORA);
        assertThat(r.endTime()).isEqualTo(LocalTime.of(15, 0));
    }

    @Test
    void guardaElProgresoPasoAPasoYNoSaltaEstados() {
        var r = caso.execute(pedido("ana"));

        assertThat(reservations.historyOf(r.id())).containsExactly(
                ReservationStatus.STARTING, ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE);
    }

    @Test
    void guardaElProcesoAntesDePedirElHoldYAntesDeConfirmarlo() {
        List<String> visto = new ArrayList<>();
        holds.onCall = () -> visto.add(holds.calls.get(holds.calls.size() - 1) + ":"
                + reservations.rows.values().iterator().next().status());

        caso.execute(pedido("ana"));

        // Al pedir el hold el proceso ya estaba en STARTING; al confirmar, ya estaba en HELD.
        assertThat(visto).containsExactly("createHold:STARTING", "confirmHold:HELD");
    }

    @Test
    void laCatedraConoceAlPacienteComoUnIdOpacoYNoComoElLogin() {
        caso.execute(pedido("ana"));

        assertThat(holds.createArgs.get(0)[3]).isEqualTo("user-7");
        assertThat(holds.confirmArgs.get(0)[1]).isEqualTo("user-7");
        assertThat(holds.createArgs.get(0)).doesNotContain("ana");
    }

    @Test
    void laCatedraRecibeElTurnoYLosNombresSinEspaciosDeMas() {
        caso.execute(pedido("ana"));

        assertThat(holds.createArgs.get(0)).containsExactly(218L, MARTES, HORA, "user-7");
        assertThat(holds.confirmArgs.get(0)).containsExactly("hold-1", "user-7", "Juan", "Pérez");
    }

    @Test
    void alCatalogoSeLeReenviaElTokenDelUsuario() {
        caso.execute(pedido("ana"));

        assertThat(tokensAlCatalogo).containsExactly(TOKEN_DEL_USUARIO);
    }

    @Test
    void guardaLosDatosDelProfesionalComoFotoHistorica() {
        var r = caso.execute(pedido("ana"));

        assertThat(r.professionalFirstName()).isEqualTo("Romina");
        assertThat(r.professionalLastName()).isEqualTo("Acosta");
    }

    // ---- quién y qué turno ----------------------------------------------------------------------------------

    @Test
    void unUsuarioInexistenteOInactivoNoPuedeReservarYNoSeLlamaANadie() {
        for (String login : List.of("nadie", "inactivo")) {
            assertThatThrownBy(() -> caso.execute(pedido(login))).as(login)
                    .isInstanceOf(AccountUnavailableException.class);
        }
        assertThat(holds.calls).isEmpty();
        assertThat(tokensAlCatalogo).isEmpty();
        assertThat(reservations.rows).isEmpty();
    }

    @Test
    void unProfesionalDeshabilitadoNoSeReservaNiSeGuardaNada() {
        agenda = new ProfessionalAgenda(218, false, "Romina", "Acosta", agenda.schedules());

        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(ProfessionalDisabledException.class);
        assertThat(holds.calls).isEmpty();
        assertThat(reservations.rows).isEmpty();
    }

    @Test
    void unTurnoQueNoEstaEnLaAgendaSeRechazaSinLlamarALaCatedra() {
        var fueraDeGrilla = new StartReservationCommand("ana", 218, MARTES, LocalTime.of(14, 45), "Juan", "Pérez", "t");
        var otroDia = new StartReservationCommand("ana", 218, MARTES.plusDays(1), HORA, "Juan", "Pérez", "t");
        var fueraDeHorario = new StartReservationCommand("ana", 218, MARTES, LocalTime.of(16, 0), "Juan", "Pérez", "t");

        for (var comando : List.of(fueraDeGrilla, otroDia, fueraDeHorario)) {
            assertThatThrownBy(() -> caso.execute(comando)).isInstanceOf(InvalidSlotException.class);
        }
        assertThat(holds.calls).isEmpty();
        assertThat(reservations.rows).isEmpty();
    }

    @Test
    void unTurnoQueYaEmpezoSeRechaza() {
        var reloj = Clock.fixed(Instant.parse("2026-11-24T17:45:00Z"), ZoneOffset.UTC); // 14:45 en Buenos Aires
        var conReloj = armadoCon(reloj);

        assertThatThrownBy(() -> conReloj.execute(pedido("ana"))).isInstanceOf(InvalidSlotException.class);
        assertThat(holds.calls).isEmpty();
    }

    private StartReservation armadoCon(Clock reloj) {
        UserRepository users = new UserRepository() {
            public Optional<User> findByLogin(String login) { return Optional.ofNullable(usuarios.get(login)); }
            public boolean existsByLogin(String login) { return false; }
            public boolean existsByEmail(String email) { return false; }
            public User save(User user) { throw new UnsupportedOperationException(); }
        };
        return new StartReservation(users, (id, token) -> agenda, holds, reservations, reloj, BUENOS_AIRES);
    }

    @Test
    void unProfesionalInexistenteOUnCatalogoCaidoSePropaganYNoGuardanNada() {
        fallaDelCatalogo = new ProfessionalNotFoundException(218);
        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(ProfessionalNotFoundException.class);

        fallaDelCatalogo = new CatalogUnavailableException("caído", null);
        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatalogUnavailableException.class);

        assertThat(holds.calls).isEmpty();
        assertThat(reservations.rows).isEmpty();
    }

    // ---- la cátedra rechaza el hold -------------------------------------------------------------------------

    @Test
    void siLaCatedraRechazaElHoldElProcesoQuedaFallidoConSuMotivoYElErrorSePropaga() {
        holds.creates.add(rechazaHold(CatedraFailure.SLOT_ALREADY_HELD));

        assertThatThrownBy(() -> caso.execute(pedido("ana")))
                .isInstanceOfSatisfying(CatedraRejectedException.class,
                        e -> assertThat(e.failure()).isEqualTo(CatedraFailure.SLOT_ALREADY_HELD));

        var fallido = reservations.rows.values().iterator().next();
        assertThat(fallido.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(fallido.failureCode()).isEqualTo("SLOT_ALREADY_HELD");
        assertThat(fallido.holdId()).isNull();
        assertThat(holds.count("confirmHold")).isZero();
    }

    @Test
    void despuesDeUnHoldRechazadoElUsuarioPuedeVolverAIntentar() {
        holds.creates.add(rechazaHold(CatedraFailure.SLOT_ALREADY_RESERVED));
        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatedraRejectedException.class);

        var segundo = caso.execute(pedido("ana"));

        assertThat(segundo.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(reservations.rows).hasSize(2);
    }

    @Test
    void siNoSeSabeSiElHoldSeCreoElProcesoSeCierraComoFallidoYNoQuedaColgado() {
        holds.creates.add(noResponde());

        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatedraUnavailableException.class);

        var fallido = reservations.rows.values().iterator().next();
        assertThat(fallido.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(fallido.failureCode()).isEqualTo("CATEDRA_UNAVAILABLE");
        assertThat(holds.count("createHold")).as("el hold no se reintenta solo").isEqualTo(1);
    }

    // ---- la confirmación ------------------------------------------------------------------------------------

    @Test
    void siElHoldVencioAntesDeConfirmarElProcesoExpira() {
        holds.confirms.add(rechazaConfirmacion(CatedraFailure.HOLD_EXPIRED));

        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatedraRejectedException.class);

        assertThat(reservations.rows.values().iterator().next().status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void unHoldQueLaCatedraYaNoRecuperaTambienCuentaComoExpirado() {
        holds.confirms.add(rechazaConfirmacion(CatedraFailure.HOLD_NOT_FOUND));

        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatedraRejectedException.class);

        assertThat(reservations.rows.values().iterator().next().status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void otroRechazoDeLaConfirmacionDejaElProcesoFallidoConSuMotivo() {
        holds.confirms.add(rechazaConfirmacion(CatedraFailure.EXTERNAL_PATIENT_MISMATCH));

        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatedraRejectedException.class);

        var r = reservations.rows.values().iterator().next();
        assertThat(r.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(r.failureCode()).isEqualTo("EXTERNAL_PATIENT_MISMATCH");
    }

    @Test
    void siLaConfirmacionYaEstabaHechaSeTomaComoExitoYSeConservaElVencimiento() {
        holds.confirms.add(rechazaConfirmacion(CatedraFailure.RESERVATION_PROCESS_ALREADY_CONFIRMED));

        var r = caso.execute(pedido("ana"));

        assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(r.expiresAt()).isEqualTo(VENCE);
    }

    @Test
    void siNoSeSabeSiConfirmoSeReconciliaUnaVezYSiSeRecuperaSigue() {
        holds.confirms.add(noResponde());

        var r = caso.execute(pedido("ana"));

        assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(holds.count("confirmHold")).isEqualTo(2);
        assertThat(holds.count("createHold")).isEqualTo(1);
    }

    @Test
    void laReconciliacionDeUnaConfirmacionYaProcesadaNoDuplicaNada() {
        holds.confirms.add(noResponde());
        holds.confirms.add(rechazaConfirmacion(CatedraFailure.RESERVATION_PROCESS_ALREADY_CONFIRMED));

        var r = caso.execute(pedido("ana"));

        assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(reservations.rows).hasSize(1);
    }

    @Test
    void siSigueSinSaberseElProcesoQuedaEnHeldYElErrorSePropagaSinReintentarSinLimite() {
        holds.confirms.add(noResponde());
        holds.confirms.add(noResponde());

        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatedraUnavailableException.class);

        assertThat(reservations.rows.values().iterator().next().status()).isEqualTo(ReservationStatus.HELD);
        assertThat(holds.count("confirmHold")).as("dos intentos, ni uno más").isEqualTo(2);
    }

    @Test
    void repetirElPedidoDeUnProcesoQuedadoEnHeldRetomaSoloLaConfirmacion() {
        holds.confirms.add(noResponde());
        holds.confirms.add(noResponde());
        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(CatedraUnavailableException.class);

        var r = caso.execute(pedido("ana"));

        assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(holds.count("createHold")).as("no se crea otro hold").isEqualTo(1);
        assertThat(reservations.rows).hasSize(1);
    }

    // ---- el mismo pedido dos veces --------------------------------------------------------------------------

    // ---- la carrera con el consumidor de Kafka ----------------------------------------------------------------

    @Test
    void siElEventoDeLaCatedraLlegaEntreLaConfirmacionYElGuardadoNoHayErrorYSeConservaElPedidoDeTelefono() {
        // La cátedra publica AdditionalInformationRequested apenas confirma: el consumidor lo guarda antes de que
        // este pedido guarde su paso a WAITING_FOR_PHONE y le cambia la versión.
        holds.onCall = () -> {
            if (holds.count("confirmHold") == 1) {
                reservations.save(reservations.rows.get(1L).informationRequested("request-event-1", AHORA.instant()));
            }
        };
        try (var log = new ar.edu.um.cronos.LogCapture()) {
            var r = caso.execute(pedido("ana"));

            assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
            assertThat(r.requestEventId()).as("lo que guardó el consumidor no se pierde").isEqualTo("request-event-1");
            assertThat(reservations.rows.get(r.id())).isEqualTo(r);
            assertThat(reservations.historyOf(r.id())).containsExactly(ReservationStatus.STARTING,
                    ReservationStatus.HELD, ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE);
            assertThat(holds.count("confirmHold")).as("no se vuelve a llamar a la cátedra").isEqualTo(1);
            assertThat(log.eventos("reserva.confirmacion.carrera")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("estado_actual=HELD")
                            .contains("accion=se_reaplica_sobre_la_version_nueva"));
        }
    }

    @Test
    void siElEventoYaAdelantoElProcesoSeDevuelveTalCualEnLugarDePisarlo() {
        holds.onCall = () -> {
            if (holds.count("confirmHold") == 1) {
                reservations.save(reservations.rows.get(1L).informationRequested("request-event-1", AHORA.instant())
                        .waitingForPhone(VENCE, AHORA.instant()));
            }
        };

        var r = caso.execute(pedido("ana"));

        assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(r.expiresAt()).as("el que ya había guardado el consumidor").isEqualTo(VENCE);
        assertThat(r.requestEventId()).isEqualTo("request-event-1");
        assertThat(reservations.historyOf(r.id())).containsExactly(ReservationStatus.STARTING,
                ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE);
    }

    @Test
    void elMismoPedidoDosVecesNoCreaDosHoldsYDevuelveElMismoProceso() {
        var primero = caso.execute(pedido("ana"));
        var segundo = caso.execute(pedido("ana"));

        assertThat(segundo.id()).isEqualTo(primero.id());
        assertThat(segundo.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(holds.count("createHold")).isEqualTo(1);
        assertThat(holds.count("confirmHold")).isEqualTo(1);
        assertThat(reservations.rows).hasSize(1);
    }

    @Test
    void elPedidoRepetidoNoNecesitaAlCatalogo() {
        caso.execute(pedido("ana"));
        tokensAlCatalogo.clear();
        fallaDelCatalogo = new CatalogUnavailableException("caído", null);

        assertThat(caso.execute(pedido("ana")).status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(tokensAlCatalogo).isEmpty();
    }

    @Test
    void siElProcesoTodaviaEstaEnStartingOtroPedidoNoLoPisa() {
        var enCurso = Reservation.start(7, 218, "Romina", "Acosta",
                new ar.edu.um.cronos.domain.Slot(MARTES, HORA, LocalTime.of(15, 0)), "Juan", "Pérez",
                AHORA.instant());
        reservations.save(enCurso);

        assertThatThrownBy(() -> caso.execute(pedido("ana"))).isInstanceOf(ReservationInProgressException.class);
        assertThat(holds.calls).isEmpty();
    }

    @Test
    void siDosPedidosIgualesLlegaranALaVezElQuePierdeLaCarreraRetomaElGanador() {
        var ganador = Reservation.start(7, 218, "Romina", "Acosta",
                new ar.edu.um.cronos.domain.Slot(MARTES, HORA, LocalTime.of(15, 0)), "Juan", "Pérez",
                AHORA.instant()).held("hold-9", "process-9", VENCE, AHORA.instant()).waitingForPhone(VENCE,
                AHORA.instant());
        reservations.winsTheRace = ganador;

        var r = caso.execute(pedido("ana"));

        assertThat(r.reservationProcessId()).isEqualTo("process-9");
        assertThat(holds.calls).as("el perdedor no crea otro hold").isEmpty();
    }

    @Test
    void dosUsuariosPuedenPedirElMismoTurnoYCadaUnoTieneSuProcesoYSuPaciente() {
        caso.execute(pedido("ana"));
        holds.creates.add(rechazaHold(CatedraFailure.SLOT_ALREADY_HELD)); // la cátedra dice que ya está tomado

        assertThatThrownBy(() -> caso.execute(pedido("beto"))).isInstanceOf(CatedraRejectedException.class);

        assertThat(holds.createArgs.get(1)[3]).isEqualTo("user-8");
        assertThat(reservations.rows.values()).extracting(Reservation::userId).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void elProcesoDeUnUsuarioNoLoRetomaOtro() {
        var deAna = caso.execute(pedido("ana"));

        var deBeto = caso.execute(pedido("beto"));

        assertThat(deBeto.id()).isNotEqualTo(deAna.id());
        assertThat(deBeto.userId()).isEqualTo(8);
        assertThat(holds.count("createHold")).isEqualTo(2);
    }

    // ---- un proceso que ya venció ---------------------------------------------------------------------------

    private Reservation procesoConVencimiento(ReservationStatus estado, Instant vence) {
        var base = Reservation.start(7, 218, "Romina", "Acosta",
                new ar.edu.um.cronos.domain.Slot(MARTES, HORA, LocalTime.of(15, 0)), "Juan", "Pérez", AHORA.instant());
        var held = base.held("hold-viejo", "process-viejo", vence, AHORA.instant());
        var conEstado = switch (estado) {
            case HELD -> held;
            case WAITING_FOR_PHONE -> held.waitingForPhone(vence, AHORA.instant());
            case CONFIRMED -> held.waitingForPhone(vence, AHORA.instant()).confirmed(8251,
                    AHORA.instant());
            default -> throw new IllegalArgumentException();
        };
        return reservations.save(conEstado);
    }

    @Test
    void siElProcesoActivoYaVencioSeCierraYSeEmpiezaUnoNuevo() {
        var viejo = procesoConVencimiento(ReservationStatus.WAITING_FOR_PHONE, AHORA.instant().minusSeconds(1));

        var nuevo = caso.execute(pedido("ana"));

        assertThat(nuevo.id()).isNotEqualTo(viejo.id());
        assertThat(nuevo.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(reservations.rows.get(viejo.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(holds.count("createHold")).as("se pide un hold nuevo").isEqualTo(1);
    }

    @Test
    void tambienVenceUnProcesoQueQuedoEnHeld() {
        var viejo = procesoConVencimiento(ReservationStatus.HELD, AHORA.instant().minusSeconds(60));

        var nuevo = caso.execute(pedido("ana"));

        assertThat(nuevo.id()).isNotEqualTo(viejo.id());
        assertThat(reservations.rows.get(viejo.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void unVencimientoJustoAhoraYaVencio() {
        var viejo = procesoConVencimiento(ReservationStatus.WAITING_FOR_PHONE, AHORA.instant());

        caso.execute(pedido("ana"));

        assertThat(reservations.rows.get(viejo.id()).status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void unProcesoQueTodaviaNoVencioSeReutilizaSinTocarloNiPedirOtroHold() {
        var vigente = procesoConVencimiento(ReservationStatus.WAITING_FOR_PHONE, AHORA.instant().plusSeconds(60));

        var r = caso.execute(pedido("ana"));

        assertThat(r.id()).isEqualTo(vigente.id());
        assertThat(r.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(holds.calls).isEmpty();
    }

    @Test
    void unaReservaConfirmadaNoVenceAunqueSuVencimientoDelHoldYaHayaPasado() {
        var confirmada = procesoConVencimiento(ReservationStatus.CONFIRMED, AHORA.instant().minusSeconds(3600));

        var r = caso.execute(pedido("ana"));

        assertThat(r.id()).isEqualTo(confirmada.id());
        assertThat(r.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(holds.calls).isEmpty();
    }

    // ---- lo que no debe verse -------------------------------------------------------------------------------

    @Test
    void elPedidoNoImprimeNiElTokenNiLosNombres() {
        var texto = pedido("ana").toString();

        assertThat(texto).doesNotContain(TOKEN_DEL_USUARIO, "Juan", "Pérez", "ana");
    }
}
