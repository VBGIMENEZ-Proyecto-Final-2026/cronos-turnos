package ar.edu.um.cronos.application.reservation;

import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Recuperación y vencimiento de los procesos de reserva (issue #13; enunciado 8; referencia 18.3 y 18.4). Una
 * <b>pasada</b> hace tres cosas, en este orden, y se repite sola (al arrancar y cada tanto) porque no depende de que llegue
 * ningún evento:
 *
 * <ol>
 *   <li><b>Barrido de vencimiento</b>: un proceso con hold ({@code HELD} o {@code WAITING_FOR_PHONE}) cuyo
 *       {@code expiresAt} ya pasó se cierra como {@code EXPIRED} aunque no llegue {@code AppointmentProcessExpired}.
 *       No necesita a la cátedra. Se espera {@code expiryGrace} después del vencimiento: la cátedra puede haber confirmado
 *       justo antes de vencer y su evento todavía estar en camino; cerrar enseguida lo dejaría en {@code EXPIRED}
 *       (un estado final que un evento tardío no reabre) con una reserva que la cátedra sí confirmó.</li>
 *   <li><b>Un proceso {@code STARTING} viejo</b> (cronos se cortó entre guardarlo y obtener el hold) pasa a
 *       {@code FAILED} con el código {@value #INTERRUPTED}: nunca llegó a saberse si hubo hold. Si la cátedra lo creó,
 *       vence solo a los pocos minutos y el turno vuelve a verse libre.</li>
 *   <li><b>Un proceso {@code HELD} viejo</b> (se creó el hold pero no se supo si se confirmó) se reconcilia repitiendo la
 *       confirmación con los identificadores guardados: es idempotente en la cátedra y un {@code ALREADY_CONFIRMED} cuenta
 *       como éxito (un timeout no prueba que la operación falló: referencia 18.4).</li>
 * </ol>
 *
 * <p><b>Los reintentos tienen límite.</b> En una pasada cada proceso se intenta lo que la confirmación normal (el
 * primero y una reconciliación) y, si la cátedra no responde, <b>se corta la pasada</b> sin probar los demás. El
 * proceso sigue en {@code HELD} y la pasada siguiente lo reintenta, pero nunca más allá de su {@code expiresAt}: el
 * barrido lo cierra. Así ningún proceso queda colgado y la espera entre intentos es el intervalo entre pasadas.
 *
 * <p>Solo se tocan procesos <b>viejos</b> ({@code staleAfter} sin modificarse): uno recién guardado puede tener un pedido
 * del usuario en curso, y con dos escritores a la vez gana el bloqueo optimista (quien pierde relee y sigue).
 *
 * <p><b>Límite conocido:</b> no se concilia contra el listado de la cátedra ({@code GET /api/appointments}); si Kafka
 * estuvo caído más que {@code expiryGrace} después del vencimiento, una confirmación real podría llegar a un proceso ya
 * cerrado. Se documenta en la evidencia de #13.
 */
public class RecoverReservations {

    private static final Logger log = LoggerFactory.getLogger(RecoverReservations.class);
    /** Motivo estable de un proceso que nunca llegó a tener hold por un corte de cronos. */
    public static final String INTERRUPTED = "INTERRUPTED_BEFORE_HOLD";

    /**
     * @param staleAfter  cuánto sin modificarse para que un proceso a medias se considere abandonado
     * @param expiryGrace cuánto se espera después de {@code expiresAt} antes de cerrarlo por vencido
     * @param batchSize   cuántos procesos como máximo por paso de una pasada (el resto queda para la siguiente)
     */
    public record Settings(Duration staleAfter, Duration expiryGrace, int batchSize) {
        public Settings {
            if (staleAfter.isNegative() || expiryGrace.isNegative() || batchSize < 1) {
                throw new IllegalArgumentException("Parámetros de recuperación inválidos");
            }
        }
    }

    /** Lo que hizo una pasada. */
    public record Report(int expired, int interrupted, int reconciled, int closedByCatedra, int stillHeld,
                         boolean catedraUnavailable) {
        public boolean changedAnything() {
            return expired + interrupted + reconciled + closedByCatedra > 0;
        }
    }

    private final ReservationRepository reservations;
    private final StartReservation starter;
    private final Clock clock;
    private final Settings settings;

    public RecoverReservations(ReservationRepository reservations, StartReservation starter, Clock clock,
                               Settings settings) {
        this.reservations = reservations;
        this.starter = starter;
        this.clock = clock;
        this.settings = settings;
    }

    /** Una pasada completa. No lanza por la falla de un proceso: la registra y sigue con el siguiente. */
    public Report run() {
        int expired = sweepExpired();
        int interrupted = failInterrupted();
        Reconciliation held = reconcileHeld();
        Report report = new Report(expired, interrupted, held.reconciled, held.closed, held.stillHeld,
                held.catedraUnavailable);
        if (report.changedAnything() || report.stillHeld() > 0) {
            LogEvent.info(log, "reserva.recuperacion.pasada", "vencidos", expired, "interrumpidos", interrupted,
                    "reconciliados", held.reconciled, "cerrados_por_la_catedra", held.closed,
                    "siguen_en_HELD", held.stillHeld, "catedra_caida", held.catedraUnavailable);
        }
        return report;
    }

    // ---- 1. barrido de vencimiento -------------------------------------------------------------------------------

    private int sweepExpired() {
        Instant cutoff = now().minus(settings.expiryGrace());
        int closed = 0;
        for (Reservation candidate : guarded("barrido",
                () -> reservations.findExpiredPending(cutoff, settings.batchSize()))) {
            try {
                if (closeExpired(candidate, cutoff)) {
                    closed++;
                }
            } catch (RuntimeException e) {
                failure(candidate, "barrido", e);
            }
        }
        return closed;
    }

    /** Cierra un proceso vencido; si otro escritor lo cambió a la vez, lo relee y decide con lo que quedó. */
    private boolean closeExpired(Reservation candidate, Instant cutoff) {
        Reservation current = candidate;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                reservations.save(current.expired(now()));
                LogEvent.warn(log, "reserva.proceso.vencido_por_barrido", "reserva", current.id(),
                        "proceso", current.reservationProcessId(), "estado_anterior", current.status(),
                        "vencio", current.expiresAt(), "accion", "proceso_cerrado_sin_esperar_el_evento");
                return true;
            } catch (ReservationConflictException conflict) {
                Optional<Reservation> fresh = reservations.findById(current.id());
                if (fresh.isEmpty() || !stillPendingAndExpired(fresh.get(), cutoff)) {
                    LogEvent.info(log, "reserva.recuperacion.carrera", "reserva", current.id(), "paso", "barrido",
                            "estado_actual", fresh.map(Reservation::status).orElse(null),
                            "accion", "otro_escritor_ya_lo_resolvio");
                    return false;
                }
                current = fresh.get();
            }
        }
        LogEvent.warn(log, "reserva.recuperacion.carrera", "reserva", current.id(), "paso", "barrido",
                "accion", "queda_para_la_proxima_pasada");
        return false;
    }

    private static boolean stillPendingAndExpired(Reservation r, Instant cutoff) {
        return (r.status() == ReservationStatus.HELD || r.status() == ReservationStatus.WAITING_FOR_PHONE)
                && r.expiresAt() != null && !r.expiresAt().isAfter(cutoff);
    }

    // ---- 2. procesos que nunca llegaron a tener hold -------------------------------------------------------------

    private int failInterrupted() {
        Instant cutoff = now().minus(settings.staleAfter());
        int failed = 0;
        for (Reservation candidate : guarded("sin_hold",
                () -> reservations.findStale(ReservationStatus.STARTING, cutoff, settings.batchSize()))) {
            try {
                reservations.save(candidate.failed(INTERRUPTED, now()));
                failed++;
                LogEvent.warn(log, "reserva.recuperacion.sin_hold", "reserva", candidate.id(),
                        "estado_anterior", candidate.status(), "motivo", INTERRUPTED,
                        "accion", "proceso_cerrado", "nota", "si_el_hold_se_creo_vence_solo");
            } catch (ReservationConflictException conflict) {
                LogEvent.info(log, "reserva.recuperacion.carrera", "reserva", candidate.id(), "paso", "sin_hold",
                        "accion", "otro_escritor_lo_cambio_se_lo_deja");
            } catch (RuntimeException e) {
                failure(candidate, "sin_hold", e);
            }
        }
        return failed;
    }

    // ---- 3. holds sin confirmar ----------------------------------------------------------------------------------

    private static final class Reconciliation {
        int reconciled;
        int closed;
        int stillHeld;
        boolean catedraUnavailable;
    }

    private Reconciliation reconcileHeld() {
        Reconciliation result = new Reconciliation();
        Instant cutoff = now().minus(settings.staleAfter());
        List<Reservation> held = guarded("reconciliacion",
                () -> reservations.findStale(ReservationStatus.HELD, cutoff, settings.batchSize()));
        for (int i = 0; i < held.size(); i++) {
            Reservation candidate = held.get(i);
            try {
                Reservation after = starter.reconcile(candidate);
                result.reconciled++;
                LogEvent.info(log, "reserva.recuperacion.reconciliado", "reserva", candidate.id(),
                        "proceso", candidate.reservationProcessId(), "estado", after.status());
            } catch (CatedraRejectedException e) {
                // La confirmación ya dejó el proceso cerrado (vencido o fallido) y lo registró.
                result.closed++;
                LogEvent.info(log, "reserva.recuperacion.cerrado_por_la_catedra", "reserva", candidate.id(),
                        "proceso", candidate.reservationProcessId(), "motivo", e.failure());
            } catch (CatedraUnavailableException e) {
                // No se sabe qué pasó: sigue en HELD y la pasada siguiente lo reintenta, hasta su vencimiento. No se prueba con
                // los demás: si la cátedra no responde, cada uno gastaría su propio timeout.
                result.catedraUnavailable = true;
                result.stillHeld = held.size() - i;
                LogEvent.warn(log, "reserva.recuperacion.catedra_no_responde", "reserva", candidate.id(),
                        "proceso", candidate.reservationProcessId(), "pendientes", result.stillHeld,
                        "accion", "se_corta_la_pasada_y_se_reintenta_en_la_proxima");
                return result;
            } catch (ReservationConflictException conflict) {
                LogEvent.info(log, "reserva.recuperacion.carrera", "reserva", candidate.id(), "paso", "reconciliacion",
                        "accion", "otro_escritor_lo_cambio_se_lo_deja");
            } catch (RuntimeException e) {
                failure(candidate, "reconciliacion", e);
            }
        }
        return result;
    }

    // ---- ayudas --------------------------------------------------------------------------------------------------

    /** Una falla de la base al buscar no tumba la pasada: se registra y ese paso se salta. */
    private List<Reservation> guarded(String step, java.util.function.Supplier<List<Reservation>> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            LogEvent.error(log, "reserva.recuperacion.error", e, "paso", step, "etapa", "busqueda",
                    "accion", "se_salta_el_paso_y_se_reintenta_en_la_proxima_pasada");
            return List.of();
        }
    }

    private void failure(Reservation r, String step, RuntimeException e) {
        LogEvent.error(log, "reserva.recuperacion.error", e, "reserva", r.id(), "paso", step,
                "accion", "se_sigue_con_el_siguiente");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
