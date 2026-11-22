package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Event;
import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Malformed;
import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Skipped;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent.Outcome;
import ar.edu.um.cronos.logging.LogEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Recibe cada mensaje del topic de acciones y lo aplica con {@link ProcessReservationEvent}.
 *
 * <p><b>El offset se confirma solo después de la transacción.</b> {@code execute} vuelve cuando el cambio de estado y el
 * marcado del evento ya se guardaron; recién ahí se confirma. Si cronos se cae antes, el mensaje se vuelve a leer y, como
 * es idempotente, no repite ningún efecto.
 *
 * <p>Qué pasa con cada mensaje:
 * <ul>
 *   <li>{@code Malformed} (no se entiende): se registra <i>sin su contenido</i> y se confirma. Reintentarlo no lo arregla y
 *       dejaría la partición trabada.</li>
 *   <li>{@code Skipped} (un tipo que cronos no usa): se registra y se confirma.</li>
 *   <li>Evento aplicado, duplicado o ignorado: se confirma.</li>
 *   <li>Evento de un proceso desconocido: no se confirma; se lanza {@link UnknownProcessException} y el manejador de errores
 *       lo reintenta de forma acotada y, al agotarlos, lo estaciona y lo confirma.</li>
 *   <li>Cualquier otra excepción sube tal cual al manejador de errores, que decide según su tipo (ver
 *       {@link KafkaFailures}).</li>
 * </ul>
 *
 * <p>Toda línea de log mientras se procesa un mensaje lleva como identificador {@code kafka-<partición>-<offset>} (el mismo
 * lugar donde un pedido HTTP lleva su {@code requestId}). No se registra el nombre del topic ni del grupo: contienen el
 * {@code groupId} de la cuenta.
 */
public class ReservationEventsListener implements AcknowledgingMessageListener<String, String> {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventsListener.class);

    private final ReservationEventParser parser;
    private final ProcessReservationEvent process;

    public ReservationEventsListener(ReservationEventParser parser, ProcessReservationEvent process) {
        this.parser = parser;
        this.process = process;
    }

    @Override
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        MDC.put("requestId", messageId(record));
        try {
            switch (parser.parse(record.value())) {
                case Malformed malformed -> {
                    LogEvent.warn(log, "kafka.mensaje.ilegible", "particion", record.partition(), "offset",
                            record.offset(), "motivo", malformed.reason(), "campo", malformed.field(),
                            "accion", "descartado_y_offset_confirmado");
                    acknowledgment.acknowledge();
                }
                case Skipped skipped -> {
                    LogEvent.info(log, "kafka.mensaje.saltado", "particion", record.partition(), "offset",
                            record.offset(), "tipo", skipped.eventType(), "accion", "ignorado_y_offset_confirmado");
                    acknowledgment.acknowledge();
                }
                case Event event -> {
                    if (process.execute(event.event()) == Outcome.UNKNOWN_PROCESS) {
                        throw new UnknownProcessException(event.event());
                    }
                    acknowledgment.acknowledge();
                }
            }
        } finally {
            MDC.remove("requestId");
        }
    }

    /** El identificador del mensaje para el log: {@code kafka-<partición>-<offset>}. */
    static String messageId(ConsumerRecord<?, ?> record) {
        return "kafka-" + record.partition() + "-" + record.offset();
    }
}
