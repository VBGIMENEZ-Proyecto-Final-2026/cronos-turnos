package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.application.port.out.PhoneGateway;
import ar.edu.um.cronos.application.port.out.PhoneSubmission;
import ar.edu.um.cronos.application.port.out.PhoneUnavailableException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.InvalidPhoneNumberException;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Responder el pedido de teléfono con dobles: qué se exige y en qué orden, qué se manda, cuándo se marca y que el número no
 * aparece en ningún mensaje, excepción ni línea de log.
 */
class SubmitPhoneTest {

    private static final Instant T0 = Instant.parse("2026-11-22T15:00:00Z");
    private static final Clock AHORA = Clock.fixed(T0, ZoneOffset.UTC);
    private static final Instant VENCE = T0.plusSeconds(600);
    private static final String NUMERO = "+5492615551234";

    /** Un gateway que recuerda lo que se le manda y puede fallar o ejecutar algo en el momento del envío. */
    private static class RecordingGateway implements PhoneGateway {
        final List<PhoneSubmission> sent = new ArrayList<>();
        RuntimeException failure;
        Consumer<PhoneSubmission> onSubmit = s -> { };

        @Override
        public void submit(PhoneSubmission submission) {
            onSubmit.accept(submission);
            if (failure != null) {
                throw failure;
            }
            sent.add(submission);
        }
    }

    private final StartReservationTest.FakeReservations reservations = new StartReservationTest.FakeReservations();
    private final RecordingGateway gateway = new RecordingGateway();
    private final Map<String, User> usuarios = new HashMap<>();
    private final AtomicInteger envios = new AtomicInteger();
    private SubmitPhone caso;

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
        caso = new SubmitPhone(users, reservations, gateway, AHORA, () -> "phone-submitted-" + envios.incrementAndGet());
    }

    // ---- ayudas ---------------------------------------------------------------------------------------------

    private static final Slot TURNO = new Slot(LocalDate.of(2026, 11, 24), LocalTime.of(14, 30), LocalTime.of(15, 0));
    private int seq;

    private Reservation proceso(long userId, ReservationStatus estado, boolean pedido) {
        seq++;
        var r = reservations.save(Reservation.start(userId, 218, "Romina", "Acosta",
                new Slot(TURNO.date().plusDays(seq), TURNO.startTime(), TURNO.endTime()), "Juan", "Pérez", T0));
        if (estado == ReservationStatus.STARTING) {
            return r;
        }
        r = reservations.save(r.held("hold-" + seq, "process-" + seq, VENCE, T0));
        if (estado == ReservationStatus.HELD) {
            return r;
        }
        r = reservations.save(r.waitingForPhone(VENCE, T0));
        if (pedido) {
            r = reservations.save(r.informationRequested("request-event-" + seq, T0));
        }
        return switch (estado) {
            case WAITING_FOR_PHONE -> r;
            case CONFIRMED -> reservations.save(r.confirmed(8251, T0));
            case EXPIRED -> reservations.save(r.expired(T0));
            case INVALID -> reservations.save(r.invalid("X", T0));
            case CANCELLED -> reservations.save(r.confirmed(8251, T0).cancelled(T0));
            default -> throw new IllegalArgumentException(estado.name());
        };
    }

    private Reservation esperando() {
        return proceso(7, ReservationStatus.WAITING_FOR_PHONE, true);
    }

    private SubmitPhoneCommand pedido(Reservation r, String telefono) {
        return new SubmitPhoneCommand("ana", r.id(), telefono);
    }

    // ---- lo que se manda ------------------------------------------------------------------------------------

    @Test
    void mandaElTelefonoConLosDatosDelProcesoYMarcaCuandoSeEnvio() {
        var r = esperando();

        var resultado = caso.execute(pedido(r, NUMERO));

        assertThat(gateway.sent).singleElement().satisfies(s -> {
            assertThat(s.eventId()).isEqualTo("phone-submitted-1");
            assertThat(s.reservationProcessId()).isEqualTo(r.reservationProcessId());
            assertThat(s.holdId()).isEqualTo(r.holdId());
            assertThat(s.requestEventId()).isEqualTo(r.requestEventId());
            assertThat(s.phone().value()).isEqualTo(NUMERO);
            assertThat(s.occurredAt()).isEqualTo(T0);
        });
        assertThat(resultado.phoneSubmittedAt()).isEqualTo(T0);
        assertThat(resultado.status()).as("el estado lo decide la cátedra").isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(reservations.rows.get(r.id())).isEqualTo(resultado);
    }

    @Test
    void mandaElNumeroYaLimpio() {
        var r = esperando();

        caso.execute(pedido(r, "+54 9 (261) 555-1234"));

        assertThat(gateway.sent.get(0).phone().value()).isEqualTo(NUMERO);
    }

    @Test
    void cadaEnvioLlevaUnEventIdNuevoYElMismoRequestEventId() {
        var r = esperando();
        caso.execute(pedido(r, NUMERO));
        // La cátedra rechazó el teléfono: el proceso sigue esperando y se puede mandar otro.
        reservations.save(reservations.rows.get(r.id()).informationRejected("INVALID_PHONE_NUMBER", T0));

        caso.execute(pedido(r, "2615550000"));

        assertThat(gateway.sent).extracting(PhoneSubmission::eventId).containsExactly("phone-submitted-1", "phone-submitted-2");
        assertThat(gateway.sent).extracting(PhoneSubmission::requestEventId).containsOnly(r.requestEventId());
    }

    // ---- de quién es la reserva ------------------------------------------------------------------------------

    @Test
    void laReservaDeOtroUsuarioOUnaInexistenteNoExistenParaElQueLaPide() {
        var deBeto = proceso(8, ReservationStatus.WAITING_FOR_PHONE, true);

        assertThatThrownBy(() -> caso.execute(pedido(deBeto, NUMERO))).isInstanceOf(ReservationNotFoundException.class);
        assertThatThrownBy(() -> caso.execute(new SubmitPhoneCommand("ana", 98_765, NUMERO)))
                .isInstanceOf(ReservationNotFoundException.class);

        assertThat(gateway.sent).isEmpty();
        assertThat(reservations.rows.get(deBeto.id()).phoneSubmittedAt()).isNull();
    }

    @Test
    void unUsuarioInexistenteOInactivoNoPuedeMandarNada() {
        var r = esperando();

        assertThatThrownBy(() -> caso.execute(new SubmitPhoneCommand("fantasma", r.id(), NUMERO)))
                .isInstanceOf(AccountUnavailableException.class);
        assertThatThrownBy(() -> caso.execute(new SubmitPhoneCommand("inactivo", r.id(), NUMERO)))
                .isInstanceOf(AccountUnavailableException.class);
        assertThat(gateway.sent).isEmpty();
    }

    // ---- en qué estado tiene que estar -----------------------------------------------------------------------

    @Test
    void siLaCatedraTodaviaNoPidioElTelefonoNoSeManda() {
        var enHeld = proceso(7, ReservationStatus.HELD, false);
        var sinPedido = proceso(7, ReservationStatus.WAITING_FOR_PHONE, false);

        assertThatThrownBy(() -> caso.execute(pedido(enHeld, NUMERO))).isInstanceOf(PhoneNotRequestedException.class);
        assertThatThrownBy(() -> caso.execute(pedido(sinPedido, NUMERO))).isInstanceOf(PhoneNotRequestedException.class);
        assertThat(gateway.sent).isEmpty();
    }

    @Test
    void unaReservaQueYaNoEsperaElTelefonoNoLoRecibe() {
        for (var estado : List.of(ReservationStatus.CONFIRMED, ReservationStatus.EXPIRED, ReservationStatus.INVALID,
                ReservationStatus.CANCELLED, ReservationStatus.STARTING)) {
            var r = proceso(7, estado, true);

            assertThatThrownBy(() -> caso.execute(pedido(r, NUMERO))).as(estado.name())
                    .isInstanceOfSatisfying(ReservationNotAwaitingPhoneException.class, e ->
                            assertThat(e.status()).isEqualTo(estado));
        }
        assertThat(gateway.sent).isEmpty();
    }

    @Test
    void unProcesoVencidoNoRecibeElTelefonoAunqueLaCatedraTodaviaNoLoCerro() {
        var r = esperando();
        var casoTarde = new SubmitPhone(usuariosRepo(), reservations, gateway, Clock.fixed(VENCE, ZoneOffset.UTC));
        var casoJustoAntes = new SubmitPhone(usuariosRepo(), reservations, gateway,
                Clock.fixed(VENCE.minusNanos(1_000), ZoneOffset.UTC));

        assertThatThrownBy(() -> casoTarde.execute(pedido(r, NUMERO))).isInstanceOf(ReservationExpiredException.class);
        assertThat(gateway.sent).isEmpty();
        assertThat(reservations.rows.get(r.id()).status()).as("no se cambia el estado: lo cierra el evento de la cátedra")
                .isEqualTo(ReservationStatus.WAITING_FOR_PHONE);

        casoJustoAntes.execute(pedido(r, NUMERO));
        assertThat(gateway.sent).hasSize(1);
    }

    private UserRepository usuariosRepo() {
        return new UserRepository() {
            public Optional<User> findByLogin(String login) { return Optional.ofNullable(usuarios.get(login)); }
            public boolean existsByLogin(String login) { return false; }
            public boolean existsByEmail(String email) { return false; }
            public User save(User user) { throw new UnsupportedOperationException(); }
        };
    }

    @Test
    void noSeMandaOtroTelefonoMientrasLaCatedraNoRespondioAlAnterior() {
        var r = esperando();
        caso.execute(pedido(r, NUMERO));

        assertThatThrownBy(() -> caso.execute(pedido(r, "2615550000"))).isInstanceOf(PhoneAlreadySubmittedException.class);

        assertThat(gateway.sent).hasSize(1);
    }

    // ---- el formato ------------------------------------------------------------------------------------------

    @Test
    void unFormatoInvalidoSeRechazaSinMandarNiMarcarNada() {
        var r = esperando();

        for (String malo : new String[]{null, "", "abc", "123", "26155a1234", "1".repeat(50)}) {
            assertThatThrownBy(() -> caso.execute(pedido(r, malo))).isInstanceOf(InvalidPhoneNumberException.class);
        }
        assertThat(gateway.sent).isEmpty();
        assertThat(reservations.rows.get(r.id()).phoneSubmittedAt()).isNull();
    }

    @Test
    void elEstadoSeJuzgaAntesQueElFormato() {
        // Un teléfono malo sobre una reserva ya confirmada es un 409, no un 400: primero cuenta qué se puede hacer.
        var confirmada = proceso(7, ReservationStatus.CONFIRMED, true);

        assertThatThrownBy(() -> caso.execute(pedido(confirmada, "abc"))).isInstanceOf(ReservationNotAwaitingPhoneException.class);
    }

    // ---- si Kafka falla --------------------------------------------------------------------------------------

    @Test
    void siKafkaNoConfirmaNoSeMarcaNadaYElUsuarioPuedeReintentar() {
        var r = esperando();
        gateway.failure = new PhoneUnavailableException(new IllegalStateException("broker caído"));
        try (var log = new LogCapture()) {
            assertThatThrownBy(() -> caso.execute(pedido(r, NUMERO))).isInstanceOf(PhoneUnavailableException.class);

            assertThat(reservations.rows.get(r.id()).phoneSubmittedAt()).isNull();
            assertThat(log.eventos("reserva.telefono.no_enviado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("motivo=IllegalStateException")
                            .contains("accion=no_se_marco_nada_el_usuario_puede_reintentar"));
        }
        gateway.failure = null;

        caso.execute(pedido(r, NUMERO));

        assertThat(gateway.sent).hasSize(1);
        assertThat(reservations.rows.get(r.id()).phoneSubmittedAt()).isEqualTo(T0);
    }

    // ---- si cambia a la vez ----------------------------------------------------------------------------------

    @Test
    void siLaCatedraYaRespondioMientrasSeEnviabaSeDevuelveElProcesoComoQuedoSinError() {
        var r = esperando();
        // El consumidor aplica la confirmación entre el envío y el guardado de la marca.
        gateway.onSubmit = s -> reservations.save(reservations.rows.get(r.id()).confirmed(8251, T0));

        var resultado = caso.execute(pedido(r, NUMERO));

        assertThat(resultado.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(gateway.sent).hasSize(1);
    }

    @Test
    void siElConsumidorCambioOtraCosaALaVezSeVuelveAMarcarSobreLaVersionNueva() {
        var r = esperando();
        gateway.onSubmit = s -> reservations.save(reservations.rows.get(r.id()).informationRejected("OTHER", T0));

        var resultado = caso.execute(pedido(r, NUMERO));

        assertThat(resultado.phoneSubmittedAt()).isEqualTo(T0);
        assertThat(resultado.lastRejectionReason()).as("lo que guardó el consumidor no se pierde").isEqualTo("OTHER");
    }

    @Test
    void siNoSePuedeGuardarLaMarcaElEnvioYaSalioYNoSeFalla() {
        var r = esperando();
        var sinGuardar = new StartReservationTest.FakeReservations() {
            @Override
            public Reservation save(Reservation reservation) {
                if (reservation.phoneSubmittedAt() != null) {
                    throw new IllegalStateException("la base no responde");
                }
                return super.save(reservation);
            }
        };
        sinGuardar.rows.putAll(reservations.rows);
        var otro = new SubmitPhone(usuariosRepo(), sinGuardar, gateway, AHORA, () -> "phone-submitted-x");
        try (var log = new LogCapture()) {
            var resultado = otro.execute(pedido(r, NUMERO));

            assertThat(resultado.phoneSubmittedAt()).isNull();
            assertThat(gateway.sent).hasSize(1);
            assertThat(log.eventos("reserva.telefono.enviado_sin_marca")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("motivo=IllegalStateException")
                            .contains("accion=el_envio_salio_la_marca_no_se_guardo"));
        }
    }

    @Test
    void siElConflictoSeRepiteDosVecesNoSeFalla() {
        var r = esperando();
        var siempreChoca = new StartReservationTest.FakeReservations() {
            @Override
            public Reservation save(Reservation reservation) {
                if (reservation.phoneSubmittedAt() != null) {
                    throw new ReservationConflictException(null);
                }
                return super.save(reservation);
            }
        };
        siempreChoca.rows.putAll(reservations.rows);
        var otro = new SubmitPhone(usuariosRepo(), siempreChoca, gateway, AHORA, () -> "phone-submitted-x");

        assertThat(otro.execute(pedido(r, NUMERO)).phoneSubmittedAt()).isNull();
        assertThat(gateway.sent).hasSize(1);
    }

    // ---- el número no se registra ----------------------------------------------------------------------------

    @Test
    void elNumeroNoApareceEnNingunLogNiMensajeNiTexto() {
        var r = esperando();
        var secreto = "+5492617778899";
        try (var log = new LogCapture()) {
            var enviado = caso.execute(pedido(r, secreto));
            assertThatThrownBy(() -> caso.execute(pedido(r, secreto))).isInstanceOf(PhoneAlreadySubmittedException.class);
            assertThatThrownBy(() -> caso.execute(pedido(esperando(), "26-secreto-99")))
                    .isInstanceOf(InvalidPhoneNumberException.class).satisfies(e ->
                            assertThat(e.getMessage()).doesNotContain("secreto").doesNotContain("26"));

            var cadenas = new StringBuilder(log.text()).append(enviado).append(pedido(r, secreto))
                    .append(gateway.sent.get(0));
            assertThat(cadenas.toString()).doesNotContain("5492617778899").doesNotContain("7778899");
            assertThat(log.eventos("reserva.telefono.enviado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).contains("usuario=7").contains("id_envio=phone-submitted-1"));
            assertThat(log.eventos("reserva.telefono.rechazado")).extracting(e -> e.getFormattedMessage())
                    .anySatisfy(m -> assertThat(m).contains("motivo=ya_enviado_sin_respuesta"))
                    .anySatisfy(m -> assertThat(m).contains("motivo=formato_invalido"));
        }
    }
}
