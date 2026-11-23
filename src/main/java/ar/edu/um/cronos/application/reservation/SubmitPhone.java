package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.PhoneGateway;
import ar.edu.um.cronos.application.port.out.PhoneSubmission;
import ar.edu.um.cronos.application.port.out.PhoneUnavailableException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.application.port.out.UserRepository;
import ar.edu.um.cronos.application.user.AccountUnavailableException;
import ar.edu.um.cronos.domain.InvalidPhoneNumberException;
import ar.edu.um.cronos.domain.PhoneNumber;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.domain.User;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Responde el pedido de teléfono de la cátedra: valida el número y lo manda por Kafka (referencia 15.5). El resultado final
 * (confirmación, rechazo, vencimiento) llega después por Kafka y lo aplica {@link ProcessReservationEvent}.
 *
 * <p><b>El teléfono no se guarda ni se registra.</b> Se valida, se manda y se descarta; lo único que queda es cuándo se mandó
 * ({@code phoneSubmittedAt}). Ningún mensaje de error ni línea de log lo repite.
 *
 * <p><b>Qué se exige, en este orden:</b> la reserva existe y es del usuario ({@link ReservationNotFoundException}: también
 * si es de otro); la cátedra ya pidió el teléfono ({@link PhoneNotRequestedException}); el proceso sigue esperándolo
 * ({@link ReservationNotAwaitingPhoneException}); no pasó su vencimiento ({@link ReservationExpiredException}); no se mandó
 * ya uno sin respuesta ({@link PhoneAlreadySubmittedException}); el formato es válido ({@link InvalidPhoneNumberException}).
 *
 * <p><b>Se manda primero y se marca después.</b> Si el broker no confirma, no se marca nada y el usuario puede reintentar. Si el
 * mensaje salió pero no se pudo guardar la marca, el pedido igual responde bien: avisar un error haría reenviar el teléfono.
 * La marca es una protección contra el doble envío y no una garantía: dos pedidos exactamente simultáneos podrían mandar dos
 * mensajes, que la cátedra distingue por su {@code eventId} y que cronos tolera (un resultado final no retrocede).
 */
public class SubmitPhone {

    private static final Logger log = LoggerFactory.getLogger(SubmitPhone.class);

    private final UserRepository users;
    private final ReservationRepository reservations;
    private final PhoneGateway phones;
    private final Clock clock;
    private final Supplier<String> eventIds;

    public SubmitPhone(UserRepository users, ReservationRepository reservations, PhoneGateway phones, Clock clock) {
        this(users, reservations, phones, clock, () -> "phone-submitted-" + UUID.randomUUID());
    }

    SubmitPhone(UserRepository users, ReservationRepository reservations, PhoneGateway phones, Clock clock,
                Supplier<String> eventIds) {
        this.users = users;
        this.reservations = reservations;
        this.phones = phones;
        this.clock = clock;
        this.eventIds = eventIds;
    }

    /**
     * @throws AccountUnavailableException          si el usuario del token ya no existe o no está activo
     * @throws ReservationNotFoundException         si la reserva no existe o no es del usuario
     * @throws PhoneNotRequestedException           si la cátedra todavía no pidió el teléfono
     * @throws ReservationNotAwaitingPhoneException si el proceso ya no espera un teléfono
     * @throws ReservationExpiredException          si el proceso ya venció
     * @throws PhoneAlreadySubmittedException       si ya se mandó un teléfono sin respuesta
     * @throws InvalidPhoneNumberException          si el formato no es válido
     * @throws PhoneUnavailableException            si no se pudo mandar a la cátedra
     */
    public Reservation execute(SubmitPhoneCommand command) {
        User user = users.findByLogin(User.normalize(command.login())).filter(User::activated)
                .orElseThrow(AccountUnavailableException::new);
        Reservation reservation = reservations.findById(command.reservationId())
                .filter(r -> r.userId() == user.id())
                .orElseThrow(() -> rejected(user.id(), command.reservationId(), "no_existe_o_no_es_suya",
                        new ReservationNotFoundException()));

        Instant now = now();
        if (reservation.status() == ReservationStatus.HELD
                || (reservation.status() == ReservationStatus.WAITING_FOR_PHONE && reservation.requestEventId() == null)) {
            throw rejected(user.id(), reservation.id(), "la_catedra_todavia_no_lo_pidio", new PhoneNotRequestedException());
        }
        if (reservation.status() != ReservationStatus.WAITING_FOR_PHONE) {
            throw rejected(user.id(), reservation.id(), "ya_no_lo_espera",
                    new ReservationNotAwaitingPhoneException(reservation.status()));
        }
        if (reservation.expiresAt() != null && !now.isBefore(reservation.expiresAt())) {
            throw rejected(user.id(), reservation.id(), "vencida", new ReservationExpiredException());
        }
        if (reservation.phoneSubmittedAt() != null) {
            throw rejected(user.id(), reservation.id(), "ya_enviado_sin_respuesta", new PhoneAlreadySubmittedException());
        }
        PhoneNumber phone;
        try {
            phone = PhoneNumber.parse(command.phoneNumber());
        } catch (InvalidPhoneNumberException e) {
            throw rejected(user.id(), reservation.id(), "formato_invalido", e);
        }

        PhoneSubmission submission = new PhoneSubmission(eventIds.get(), reservation.reservationProcessId(),
                reservation.holdId(), reservation.requestEventId(), phone, now);
        long start = System.nanoTime();
        try {
            phones.submit(submission);
        } catch (PhoneUnavailableException e) {
            LogEvent.warn(log, "reserva.telefono.no_enviado", "usuario", user.id(), "reserva", reservation.id(),
                    "proceso", reservation.reservationProcessId(), "motivo", rootName(e),
                    "accion", "no_se_marco_nada_el_usuario_puede_reintentar");
            throw e;
        }
        LogEvent.info(log, "reserva.telefono.enviado", "usuario", user.id(), "reserva", reservation.id(),
                "proceso", reservation.reservationProcessId(), "id_envio", submission.eventId(),
                "ms", (System.nanoTime() - start) / 1_000_000);
        return mark(reservation, now);
    }

    /** Guarda cuándo se mandó. Si no se puede, el envío ya salió: no se falla (el usuario lo reenviaría). */
    private Reservation mark(Reservation reservation, Instant now) {
        try {
            return reservations.save(reservation.phoneSubmitted(now));
        } catch (ReservationConflictException conflict) {
            // El consumidor de Kafka cambió el proceso a la vez (por ejemplo, la cátedra ya respondió).
            Optional<Reservation> fresh = reservations.findById(reservation.id());
            if (fresh.isPresent() && fresh.get().status() == ReservationStatus.WAITING_FOR_PHONE
                    && fresh.get().phoneSubmittedAt() == null) {
                try {
                    return reservations.save(fresh.get().phoneSubmitted(now));
                } catch (RuntimeException second) {
                    return unmarked(reservation, second);
                }
            }
            return fresh.orElse(reservation);
        } catch (RuntimeException e) {
            return unmarked(reservation, e);
        }
    }

    private Reservation unmarked(Reservation reservation, RuntimeException cause) {
        LogEvent.warn(log, "reserva.telefono.enviado_sin_marca", "reserva", reservation.id(),
                "proceso", reservation.reservationProcessId(), "motivo", cause.getClass().getSimpleName(),
                "accion", "el_envio_salio_la_marca_no_se_guardo");
        return reservation;
    }

    private static <E extends RuntimeException> E rejected(long userId, Long reservationId, String reason, E exception) {
        LogEvent.info(log, "reserva.telefono.rechazado", "usuario", userId, "reserva", reservationId, "motivo", reason);
        return exception;
    }

    private static String rootName(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
