package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Event;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent;
import ar.edu.um.cronos.config.CatedraKafkaProperties;
import ar.edu.um.cronos.logging.LogEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Qué hace el consumidor cuando aplicar un mensaje falla. Con una sola partición un mensaje no se puede saltear sin romper
 * el orden de los eventos de un proceso, y descartarlo puede perder una confirmación, así que cada tipo de falla tiene su
 * propia política (ver también {@link KafkaFailures}):
 *
 * <table>
 *   <caption>Política según la falla</caption>
 *   <tr><th>Falla</th><th>Reintentos</th><th>Al agotarlos</th></tr>
 *   <tr><td>El proceso del evento no existe</td><td>acotados ({@code unknownProcessAttempts}), espera creciente</td>
 *       <td>se estaciona ({@code UNKNOWN_PROCESS}) y se confirma el offset</td></tr>
 *   <tr><td>La infraestructura no responde ahora (la base)</td><td><b>sin límite</b>, espera creciente hasta
 *       {@code maxDelay}</td><td>no se agotan: el mensaje no se pierde ni se saltea; sigue hasta que la base vuelva</td></tr>
 *   <tr><td>El mensaje falla por sí mismo</td><td>acotados ({@code processingAttempts}), espera creciente</td>
 *       <td>se estaciona ({@code PROCESSING_FAILED}) y se confirma el offset</td></tr>
 * </table>
 *
 * <p>Si estacionar falla (la base cayó justo ahí), el contenedor lo trata como una falla más y vuelve a intentarlo: tampoco
 * se confirma un offset sin dejar rastro. Nunca se registra el mensaje de una excepción de base de datos (puede traer la fila
 * con los datos del paciente) ni el nombre del topic: solo el nombre de la clase de la excepción.
 */
public class ConsumerErrorPolicy {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorPolicy.class);

    private final CatedraKafkaProperties.Retry retry;
    private final ReservationEventParser parser;
    private final ProcessReservationEvent process;

    public ConsumerErrorPolicy(CatedraKafkaProperties.Retry retry, ReservationEventParser parser,
                               ProcessReservationEvent process) {
        this.retry = retry;
        this.parser = parser;
        this.process = process;
    }

    public DefaultErrorHandler handler() {
        var handler = new DefaultErrorHandler(this::recover, new ExponentialBackOff());
        handler.setBackOffFunction((record, error) -> backOffFor(KafkaFailures.cause(error), record));
        handler.setCommitRecovered(true);
        return handler;
    }

    BackOff backOffFor(Throwable cause, ConsumerRecord<?, ?> record) {
        if (cause instanceof UnknownProcessException) {
            return logged(bounded(retry.unknownProcessAttempts()), record, "proceso_desconocido", cause);
        }
        if (KafkaFailures.isTransient(cause)) {
            return logged(unbounded(), record, "infraestructura_sin_respuesta", cause);
        }
        return logged(bounded(retry.processingAttempts()), record, "el_mensaje_falla", cause);
    }

    /**
     * Escribe una línea por cada reintento (el número de intento y cuánto se espera). Spring pide la política una sola vez
     * por mensaje que falla, no por reintento: sin esto, una falla de la base que dura horas dejaría una sola línea.
     */
    private static BackOff logged(BackOff delegate, ConsumerRecord<?, ?> record, String kind, Throwable cause) {
        return () -> {
            var execution = delegate.start();
            return new org.springframework.util.backoff.BackOffExecution() {
                private int attempt = 1;

                @Override
                public long nextBackOff() {
                    long wait = execution.nextBackOff();
                    if (wait != STOP) {
                        attempt++;
                        LogEvent.warn(log, "kafka.mensaje.reintento", "particion", record.partition(), "offset",
                                record.offset(), "tipo_de_falla", kind, "motivo", KafkaFailures.name(cause),
                                "causa_raiz", KafkaFailures.rootName(cause), "intento", attempt, "espera_ms", wait);
                    }
                    return wait;
                }
            };
        };
    }

    private BackOff bounded(int attempts) {
        var backOff = new ExponentialBackOff(retry.delay().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxDelay().toMillis());
        backOff.setMaxAttempts(attempts - 1);
        return backOff;
    }

    private BackOff unbounded() {
        var backOff = new ExponentialBackOff(retry.delay().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxDelay().toMillis());
        return backOff;
    }

    /** Se agotaron los intentos: se estaciona el evento y el contenedor confirma el offset. */
    void recover(ConsumerRecord<?, ?> record, Exception error) {
        Throwable cause = KafkaFailures.cause(error);
        boolean unknown = cause instanceof UnknownProcessException;
        String reason = unknown ? ProcessReservationEvent.UNKNOWN_PROCESS_REASON
                : ProcessReservationEvent.PROCESSING_FAILED_REASON;
        MDC.put("requestId", ReservationEventsListener.messageId(record));
        try {
            if (record.value() instanceof String value && parser.parse(value) instanceof Event event) {
                process.park(event.event(), reason);
            }
            if (unknown) {
                LogEvent.warn(log, "kafka.mensaje.agotado", "particion", record.partition(), "offset", record.offset(),
                        "motivo", reason, "accion", "estacionado_y_offset_confirmado");
            } else {
                LogEvent.error(log, "kafka.mensaje.agotado", null, "particion", record.partition(), "offset",
                        record.offset(), "motivo", reason, "excepcion", KafkaFailures.name(cause), "causa_raiz",
                        KafkaFailures.rootName(cause), "accion", "estacionado_y_offset_confirmado");
            }
        } finally {
            MDC.remove("requestId");
        }
    }
}
