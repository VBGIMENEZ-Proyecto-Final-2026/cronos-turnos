package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.LogCapture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.event.ListenerContainerIdleEvent;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Sin particiones asignadas se avisa (Kafka no responde); con particiones, un consumidor sano sin nada que leer, no. */
class ConsumerIdleLoggerTest {

    private final ConsumerIdleLogger logger = new ConsumerIdleLogger();

    private static ListenerContainerIdleEvent evento(long idleMs, Collection<TopicPartition> particiones) {
        return new ListenerContainerIdleEvent(new Object(), new Object(), idleMs, "id", particiones,
                mock(Consumer.class), false);
    }

    @Test
    void sinParticionesAvisaConLosSegundosYLoQueHaceCronos() {
        try (var log = new LogCapture()) {
            logger.onApplicationEvent(evento(45_000, List.of()));

            assertThat(log.eventos("kafka.consumidor.sin_particiones")).singleElement().satisfies(e -> {
                assertThat(e.getLevel().toString()).isEqualTo("WARN");
                assertThat(e.getFormattedMessage()).contains("segundos_sin_asignacion=45")
                        .contains("accion=cronos_sigue_atendiendo_pedidos_y_el_consumidor_reintenta_solo");
            });
        }
    }

    @Test
    void conParticionesAsignadasNoEscribeNada() {
        try (var log = new LogCapture()) {
            logger.onApplicationEvent(evento(45_000, List.of(new TopicPartition("un-topic-con-el-group-id", 0))));

            assertThat(log.events()).isZero();
        }
    }

    @Test
    void sinLaListaDeParticionesTambienAvisa() {
        try (var log = new LogCapture()) {
            logger.onApplicationEvent(evento(1_000, null));

            assertThat(log.eventos("kafka.consumidor.sin_particiones")).hasSize(1);
        }
    }

    @Test
    void nuncaEscribeElNombreDelTopic() {
        try (var log = new LogCapture()) {
            logger.onApplicationEvent(evento(5_000, List.of()));

            assertThat(log.text()).doesNotContain("topic");
        }
    }
}
