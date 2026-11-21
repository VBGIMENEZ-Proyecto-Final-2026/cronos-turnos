package ar.edu.um.cronos.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estado local de un proceso de reserva (enunciado 7). Cronos es dueño de este estado: la cátedra informa por REST y
 * por Kafka en momentos distintos y el estado local converge sin retroceder.
 *
 * <pre>
 *   STARTING ─► HELD ─► WAITING_FOR_PHONE ─► CONFIRMED ─► CANCELLED
 *       │         │            ├─► REJECTED
 *       │         │            ├─► EXPIRED
 *       │         ├─► EXPIRED  ├─► INVALID
 *       └─► FAILED ◄──────────┴─► FAILED
 * </pre>
 *
 * <ul>
 *   <li>{@code STARTING}: el proceso está registrado en cronos, todavía sin hold en la cátedra (se guarda antes de
 *       llamarla, para no perder el rastro si algo se corta).</li>
 *   <li>{@code HELD}: la cátedra creó el hold; falta confirmarlo.</li>
 *   <li>{@code WAITING_FOR_PHONE}: el hold se confirmó inicialmente; la cátedra espera la información adicional.</li>
 *   <li>{@code CONFIRMED}: reserva confirmada. Es el único estado no final desde el que se puede seguir: cancelar.</li>
 *   <li>Finales: {@code REJECTED} (información adicional rechazada), {@code EXPIRED}, {@code INVALID},
 *       {@code CANCELLED} y {@code FAILED} (no se pudo completar; el motivo queda en {@code failureCode}).</li>
 * </ul>
 */
public enum ReservationStatus {
    STARTING,
    HELD,
    WAITING_FOR_PHONE,
    CONFIRMED,
    REJECTED,
    EXPIRED,
    INVALID,
    CANCELLED,
    FAILED;

    private Set<ReservationStatus> next() {
        return switch (this) {
            case STARTING -> EnumSet.of(HELD, FAILED);
            case HELD -> EnumSet.of(WAITING_FOR_PHONE, EXPIRED, INVALID, FAILED);
            case WAITING_FOR_PHONE -> EnumSet.of(CONFIRMED, REJECTED, EXPIRED, INVALID, FAILED);
            case CONFIRMED -> EnumSet.of(CANCELLED);
            case REJECTED, EXPIRED, INVALID, CANCELLED, FAILED -> EnumSet.noneOf(ReservationStatus.class);
        };
    }

    public boolean canTransitionTo(ReservationStatus target) {
        return next().contains(target);
    }

    /** Un estado final no admite ninguna transición: un evento tardío no puede reabrirlo. */
    public boolean isFinal() {
        return next().isEmpty();
    }

    /** Si el proceso todavía ocupa el turno del usuario (no terminó mal ni se cerró): no se puede iniciar otro igual. */
    public boolean occupiesSlot() {
        return this == STARTING || this == HELD || this == WAITING_FOR_PHONE || this == CONFIRMED;
    }
}
