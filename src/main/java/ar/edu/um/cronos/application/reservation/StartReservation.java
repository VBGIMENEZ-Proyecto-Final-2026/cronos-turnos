package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.ActiveReservationExistsException;
import ar.edu.um.cronos.application.port.out.CatalogClient;
import ar.edu.um.cronos.application.port.out.CatedraFailure;
import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.HoldGateway;
import ar.edu.um.cronos.application.port.out.HoldGateway.HoldConfirmed;
import ar.edu.um.cronos.application.port.out.HoldGateway.HoldCreated;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.AvailabilityCalculator;
import ar.edu.um.cronos.domain.ProfessionalAgenda;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.Slot;
import ar.edu.um.cronos.domain.User;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Inicia una reserva (enunciado 7): valida el turno contra el catálogo, pide un hold a la cátedra y lo confirma
 * inicialmente. Deja un proceso local asociado al usuario que lo pidió; lo que sigue (información adicional y
 * resultado final) llega por Kafka.
 *
 * <p>El progreso se guarda <b>antes</b> de cada paso con efectos, y nunca dentro de una transacción de base de datos
 * mientras se espera a la red:
 * <ol>
 *   <li>{@code STARTING}: el proceso queda registrado en cronos.</li>
 *   <li>Se pide el hold. Si la cátedra lo rechaza, el proceso pasa a {@code FAILED}. Si <em>no se sabe</em> si lo
 *       creó (no respondió), también pasa a {@code FAILED}: el hold, si existe, vence solo.</li>
 *   <li>{@code HELD}: se guardan el {@code holdId} y el {@code reservationProcessId}.</li>
 *   <li>Se confirma el hold. Si no se sabe si lo confirmó, <b>se reconcilia una vez</b> (repetir la confirmación es
 *       seguro: un {@code RESERVATION_PROCESS_ALREADY_CONFIRMED} cuenta como éxito) y, si sigue sin saberse, el
 *       proceso queda en {@code HELD}. Volver a mandar el mismo pedido retoma la confirmación.</li>
 *   <li>{@code WAITING_FOR_PHONE}.</li>
 * </ol>
 *
 * <p>Pedir dos veces el mismo turno no crea dos holds: el proceso activo del usuario se reutiliza (y la base lo
 * impide con un índice único parcial aunque dos pedidos lleguen a la vez). Si ese proceso ya pasó su vencimiento, se
 * cierra como {@code EXPIRED} y se empieza uno nuevo.
 *
 * <p>El token técnico de la cátedra vive en el adaptador de {@link HoldGateway}; esta clase no lo conoce.
 */
public class StartReservation {

    private static final Logger log = LoggerFactory.getLogger(StartReservation.class);
    /** Intentos de confirmación cuando no se sabe si la cátedra la procesó: el primero y una reconciliación. */
    private static final int CONFIRM_ATTEMPTS = 2;

    private final UserRepository users;
    private final CatalogClient catalog;
    private final HoldGateway holds;
    private final ReservationRepository reservations;
    private final Clock clock;
    private final ZoneId clinicZone;

    public StartReservation(UserRepository users, CatalogClient catalog, HoldGateway holds,
                            ReservationRepository reservations, Clock clock, ZoneId clinicZone) {
        this.users = users;
        this.catalog = catalog;
        this.holds = holds;
        this.reservations = reservations;
        this.clock = clock;
        this.clinicZone = clinicZone;
    }

    /**
     * @throws AccountUnavailableException      si el usuario del token ya no existe o no está activo
     * @throws ReservationInProgressException   si el mismo pedido todavía se está procesando
     * @throws ProfessionalDisabledException    si el profesional está deshabilitado
     * @throws InvalidSlotException             si el turno no existe en la agenda o ya empezó
     * @throws CatedraRejectedException         si la cátedra rechazó el hold o la confirmación
     * @throws CatedraUnavailableException      si no se pudo hablar con la cátedra
     */
    public Reservation execute(StartReservationCommand command) {
        User user = users.findByLogin(User.normalize(command.login())).filter(User::activated)
                .orElseThrow(AccountUnavailableException::new);
        String patientId = Reservation.externalPatientId(user.id());

        // Un pedido repetido retoma el proceso que ya existe en lugar de crear otro hold.
        Optional<Reservation> existing = reservations.findActive(user.id(), command.professionalId(),
                command.date(), command.startTime());
        if (existing.isPresent()) {
            if (!hasExpired(existing.get())) {
                return resume(existing.get(), patientId);
            }
            // Su vencimiento ya pasó (la cátedra lo dio por vencido aunque cronos todavía no se enteró por Kafka): se
            // cierra y se puede volver a pedir el turno, en lugar de devolver un proceso que ya no existe.
            reservations.save(existing.get().expired(now()));
            LogEvent.info(log, "reserva.proceso.vencido_local", "usuario", user.id(), "reserva", existing.get().id(),
                    "proceso", existing.get().reservationProcessId(), "accion", "se_empieza_uno_nuevo");
        }

        ProfessionalAgenda agenda = catalog.findAgenda(command.professionalId(), command.userToken());
        Slot slot = offered(agenda, command);

        Reservation starting;
        try {
            starting = reservations.save(Reservation.start(user.id(), command.professionalId(),
                    agenda.firstName(), agenda.lastName(), slot, command.patientFirstName().strip(),
                    command.patientLastName().strip(), now()));
        } catch (ActiveReservationExistsException e) {
            // Otro pedido igual ganó la carrera entre el chequeo y la inserción.
            return resume(reservations.findActive(user.id(), command.professionalId(), command.date(),
                    command.startTime()).orElseThrow(ReservationInProgressException::new), patientId);
        }

        LogEvent.info(log, "reserva.proceso.iniciado", "usuario", user.id(), "reserva", starting.id(),
                "profesional", starting.professionalId(), "fecha", starting.date(), "hora", starting.startTime());

        HoldCreated hold = requestHold(starting, patientId);
        Reservation held = reservations.save(starting.held(hold.holdId(), hold.reservationProcessId(),
                hold.expiresAt(), now()));
        LogEvent.info(log, "reserva.hold.creado", "usuario", user.id(), "reserva", held.id(),
                "proceso", held.reservationProcessId(), "hold", held.holdId(), "vence", held.expiresAt());
        return confirm(held, patientId);
    }

    /**
     * Retoma la confirmación de un proceso que quedó en {@code HELD} (la recuperación, no un pedido del usuario): repite
     * la confirmación del hold, que es idempotente en la cátedra. Hace lo mismo que cuando el usuario repite el pedido:
     * guarda el resultado ({@code WAITING_FOR_PHONE}, o {@code EXPIRED} / {@code FAILED} si la cátedra lo dio por
     * terminado) y registra cada intento.
     *
     * @throws CatedraRejectedException    si la cátedra rechazó la confirmación (el proceso ya quedó cerrado)
     * @throws CatedraUnavailableException si sigue sin saberse (el proceso sigue en {@code HELD})
     */
    public Reservation reconcile(Reservation held) {
        if (held.status() != ReservationStatus.HELD) {
            throw new IllegalArgumentException("Solo se reconcilia un proceso en HELD, no en " + held.status());
        }
        return confirm(held, Reservation.externalPatientId(held.userId()));
    }

    /**
     * El momento actual a la precisión de la base (microsegundos): lo que se devuelve al guardar es idéntico a lo que
     * se lee después, y un pedido repetido responde exactamente lo mismo.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Si un proceso con hold ya pasó su vencimiento. Solo cuentan {@code HELD} y {@code WAITING_FOR_PHONE}: una
     * reserva {@code CONFIRMED} no vence, y un {@code STARTING} todavía no tiene vencimiento.
     */
    private boolean hasExpired(Reservation r) {
        return (r.status() == ReservationStatus.HELD || r.status() == ReservationStatus.WAITING_FOR_PHONE)
                && r.expiresAt() != null && !r.expiresAt().isAfter(now());
    }

    /** Qué hacer con un proceso activo que ya existía. */
    private Reservation resume(Reservation existing, String patientId) {
        return switch (existing.status()) {
            // Otro pedido lo está procesando ahora mismo (o quedó cortado antes del hold): no se pisa.
            case STARTING -> {
                LogEvent.warn(log, "reserva.pedido.repetido", "usuario", existing.userId(), "reserva", existing.id(),
                        "estado", existing.status(), "accion", "rechazado_409_sigue_en_curso");
                throw new ReservationInProgressException();
            }
            // Quedó creado el hold pero no se supo si se confirmó: se retoma la confirmación.
            case HELD -> {
                LogEvent.info(log, "reserva.pedido.repetido", "usuario", existing.userId(), "reserva", existing.id(),
                        "proceso", existing.reservationProcessId(), "estado", existing.status(),
                        "accion", "se_retoma_la_confirmacion");
                yield confirm(existing, patientId);
            }
            // Ya avanzó: se devuelve tal cual.
            default -> {
                LogEvent.info(log, "reserva.pedido.repetido", "usuario", existing.userId(), "reserva", existing.id(),
                        "proceso", existing.reservationProcessId(), "estado", existing.status(),
                        "accion", "se_devuelve_el_existente");
                yield existing;
            }
        };
    }

    private Slot offered(ProfessionalAgenda agenda, StartReservationCommand command) {
        if (!agenda.enabled()) {
            throw new ProfessionalDisabledException();
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(clinicZone));
        List<Slot> candidates = AvailabilityCalculator.free(agenda, List.of(), command.date(), command.date(), now);
        return candidates.stream().filter(s -> s.startTime().equals(command.startTime())).findFirst()
                .orElseThrow(InvalidSlotException::new);
    }

    private HoldCreated requestHold(Reservation starting, String patientId) {
        try {
            return holds.createHold(starting.professionalId(), starting.date(), starting.startTime(), patientId);
        } catch (CatedraRejectedException e) {
            reservations.save(starting.failed(e.failure().name(), now()));
            LogEvent.info(log, "reserva.proceso.fallido", "usuario", starting.userId(), "reserva", starting.id(),
                    "etapa", "hold", "motivo", e.failure(), "accion", "proceso_cerrado");
            throw e;
        } catch (CatedraUnavailableException e) {
            // No se sabe si el hold existe. Se cierra el proceso para no dejarlo colgado; si el hold se creó, vence
            // solo y reaparece como ocupado en la disponibilidad hasta entonces.
            reservations.save(starting.failed("CATEDRA_UNAVAILABLE", now()));
            LogEvent.warn(log, "reserva.proceso.fallido", "usuario", starting.userId(), "reserva", starting.id(),
                    "etapa", "hold", "motivo", "CATEDRA_UNAVAILABLE", "accion", "proceso_cerrado",
                    "nota", "el_hold_puede_existir_y_vence_solo");
            throw e;
        }
    }

    private Reservation confirm(Reservation held, String patientId) {
        HoldConfirmed confirmed = null;
        for (int attempt = 1; confirmed == null; attempt++) {
            try {
                confirmed = holds.confirmHold(held.holdId(), patientId, held.patientFirstName(),
                        held.patientLastName());
            } catch (CatedraRejectedException e) {
                if (e.failure() == CatedraFailure.RESERVATION_PROCESS_ALREADY_CONFIRMED) {
                    // Una confirmación anterior sí llegó: repetirla no cambia nada.
                    LogEvent.info(log, "reserva.confirmacion.ya_aplicada", "usuario", held.userId(),
                            "reserva", held.id(), "proceso", held.reservationProcessId());
                    confirmed = new HoldConfirmed(held.reservationProcessId(), null);
                } else if (e.failure().holdIsGone()) {
                    reservations.save(held.expired(now()));
                    LogEvent.info(log, "reserva.proceso.vencido", "usuario", held.userId(), "reserva", held.id(),
                            "proceso", held.reservationProcessId(), "etapa", "confirmacion", "motivo", e.failure(),
                            "accion", "proceso_cerrado");
                    throw e;
                } else {
                    reservations.save(held.failed(e.failure().name(), now()));
                    LogEvent.info(log, "reserva.proceso.fallido", "usuario", held.userId(), "reserva", held.id(),
                            "proceso", held.reservationProcessId(), "etapa", "confirmacion", "motivo", e.failure(),
                            "accion", "proceso_cerrado");
                    throw e;
                }
            } catch (CatedraUnavailableException e) {
                if (attempt >= CONFIRM_ATTEMPTS) {
                    // Sigue sin saberse: se deja en HELD y repetir el pedido retoma desde acá.
                    LogEvent.warn(log, "reserva.confirmacion.incierta", "usuario", held.userId(),
                            "reserva", held.id(), "proceso", held.reservationProcessId(), "intentos", attempt,
                            "accion", "queda_en_HELD_repetir_el_pedido_la_retoma");
                    throw e;
                }
                LogEvent.info(log, "reserva.confirmacion.reconciliando", "usuario", held.userId(),
                        "reserva", held.id(), "proceso", held.reservationProcessId(), "intento", attempt + 1);
            }
        }
        Reservation waiting = saveWaiting(held, confirmed.expiresAt());
        LogEvent.info(log, "reserva.hold.confirmado", "usuario", waiting.userId(), "reserva", waiting.id(),
                "proceso", waiting.reservationProcessId(), "estado", waiting.status(), "vence", waiting.expiresAt(),
                "siguiente", "espera_el_telefono");
        return waiting;
    }

    /**
     * Guarda el paso a {@code WAITING_FOR_PHONE}. La cátedra publica {@code AdditionalInformationRequested} apenas
     * confirma, y el consumidor de Kafka puede guardar el {@code requestEventId} <i>entre</i> la respuesta REST y este
     * guardado: el bloqueo optimista lo detecta aunque la cátedra ya haya confirmado. No es un error del usuario: se
     * relee el proceso y, si sigue en {@code HELD}, se reaplica el paso sobre la versión nueva (que ya trae el
     * {@code requestEventId}); si el evento ya lo adelantó, se devuelve tal cual.
     */
    private Reservation saveWaiting(Reservation held, Instant expiresAt) {
        try {
            return reservations.save(held.waitingForPhone(expiresAt, now()));
        } catch (ReservationConflictException conflict) {
            Reservation fresh = reservations.findById(held.id()).orElseThrow(() -> conflict);
            LogEvent.info(log, "reserva.confirmacion.carrera", "usuario", held.userId(), "reserva", held.id(),
                    "proceso", held.reservationProcessId(), "estado_actual", fresh.status(),
                    "accion", fresh.status() == ReservationStatus.HELD ? "se_reaplica_sobre_la_version_nueva"
                            : "se_devuelve_el_proceso_ya_avanzado");
            return fresh.status() == ReservationStatus.HELD
                    ? reservations.save(fresh.waitingForPhone(expiresAt, now())) : fresh;
        }
    }
}
