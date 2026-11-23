package ar.edu.um.cronos.adapters.in.scheduling;

import ar.edu.um.cronos.application.reservation.RecoverReservations;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Corre la recuperación de las reservas: una pasada apenas arranca la aplicación (recuperar lo que quedó pendiente de la
 * ejecución anterior) y otra cada {@code interval} (el barrido de vencimiento no depende de ningún evento).
 *
 * <p>Corre en un hilo propio: si la cátedra no responde, la espera no demora el arranque ni los pedidos de los usuarios. La
 * pasada siguiente empieza {@code interval} después de que termina la anterior (nunca dos a la vez), y una falla no corta
 * la repetición: se registra y se sigue.
 */
public class RecoveryScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final RecoverReservations recovery;
    private final Duration interval;
    private volatile ScheduledExecutorService executor;
    private volatile boolean running;

    public RecoveryScheduler(RecoverReservations recovery, Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("El intervalo de recuperación debe ser positivo");
        }
        this.recovery = recovery;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reservation-recovery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pass, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        LogEvent.info(log, "reserva.recuperacion.iniciada", "cada", interval);
    }

    /** Una pasada que nunca lanza: si lanzara, el ejecutor cancelaría las siguientes. */
    void pass() {
        try {
            recovery.run();
        } catch (Throwable t) {
            LogEvent.error(log, "reserva.recuperacion.error", t, "paso", "pasada",
                    "accion", "se_reintenta_en_la_proxima_pasada");
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            LogEvent.info(log, "reserva.recuperacion.detenida");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
