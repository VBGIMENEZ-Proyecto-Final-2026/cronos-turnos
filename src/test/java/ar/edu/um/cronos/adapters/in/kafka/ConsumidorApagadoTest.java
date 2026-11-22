package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.config.CatedraKafkaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Con {@code catedra.kafka.consumer.enabled=false} (lo que usan las pruebas de contexto) no existe ninguna pieza del
 * consumidor: ni el contenedor, ni su ciclo de vida, ni se exige la configuración de Kafka. Cronos sin Kafka sirve todo lo
 * demás.
 */
@SpringBootTest
@IntegrationTestSupport
class ConsumidorApagadoTest {

    @Autowired ApplicationContext context;

    @Test
    void conElConsumidorApagadoNoHayContenedorNiCicloDeVidaNiPropiedadesDeKafka() {
        assertThat(context.getBeansOfType(KafkaMessageListenerContainer.class)).isEmpty();
        assertThat(context.getBeansOfType(ResilientConsumerLifecycle.class)).isEmpty();
        assertThat(context.getBeansOfType(ReservationEventParser.class)).isEmpty();
        assertThat(context.getBeansOfType(CatedraKafkaProperties.class)).isEmpty();
    }

    @Test
    void elCasoDeUsoDeAplicarEventosExisteIgualParaQuienLoQuieraUsar() {
        assertThat(context.getBeansOfType(ar.edu.um.cronos.application.reservation.ProcessReservationEvent.class))
                .hasSize(1);
    }
}
