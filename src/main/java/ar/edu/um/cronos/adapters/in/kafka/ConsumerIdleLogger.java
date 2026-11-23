package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.kafka.event.ListenerContainerIdleEvent;

/**
 * Avisa cuando el consumidor lleva un rato sin particiones asignadas: es lo que se ve cuando Kafka no responde (la red de la
 * cátedra caída, por ejemplo) y es la señal que reemplaza a los avisos de la librería, que se silencian porque escriben el
 * nombre del grupo y la dirección del servidor en cada reintento de conexión.
 *
 * <p>El contenedor publica este evento cada {@code catedra.kafka.idle-event-interval} sin mensajes. Con particiones asignadas
 * es simplemente un consumidor sano sin nada que leer y no escribe nada.
 */
public class ConsumerIdleLogger implements ApplicationListener<ListenerContainerIdleEvent> {

    private static final Logger log = LoggerFactory.getLogger(ConsumerIdleLogger.class);

    @Override
    public void onApplicationEvent(ListenerContainerIdleEvent event) {
        if (event.getTopicPartitions() == null || event.getTopicPartitions().isEmpty()) {
            LogEvent.warn(log, "kafka.consumidor.sin_particiones", "segundos_sin_asignacion", event.getIdleTime() / 1000,
                    "accion", "cronos_sigue_atendiendo_pedidos_y_el_consumidor_reintenta_solo");
        }
    }
}
