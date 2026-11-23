package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La aplicación completa arranca con el consumidor encendido aunque Kafka no responda: el servidor existe en el nombre
 * pero nadie escucha en ese puerto. El consumidor queda intentando conectarse en segundo plano y cronos sigue atendiendo
 * pedidos (solo se atrasa el resultado final de las reservas, que llega cuando Kafka vuelve).
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, ConsumidorKafkaInaccesibleTest.ConKafkaCaido.class})
// El consumidor sigue intentando conectarse mientras el contexto vive: se cierra al terminar la clase.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConsumidorKafkaInaccesibleTest {

    @Order(1)
    static class ConKafkaCaido implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "catedra.kafka.consumer.enabled=true",
                    "catedra.kafka.bootstrap-servers=localhost:1",
                    "catedra.kafka.actions-topic=alumnos.turnos.acciones.cuenta-de-prueba",
                    "catedra.kafka.consumer-group-id=alumnos-prueba-inaccesible",
                    // El aviso de "sin particiones" cada segundo, para no esperar 30 en la prueba.
                    "catedra.kafka.idle-event-interval=1s").applyTo(context);
        }
    }

    @Autowired KafkaMessageListenerContainer<String, String> consumidor;
    @Autowired MockMvc mockMvc;

    @Test
    void laAplicacionArrancaYElConsumidorQuedaReintentandoLaConexion() throws Exception {
        await().atMost(Duration.ofSeconds(30)).until(consumidor::isRunning);

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(consumidor.getAssignedPartitions()).isNullOrEmpty();
    }

    @Test
    void conKafkaCaidoElLogNoEscribeElGrupoNiElTopicNiLaDireccionPeroSiAvisaQueNoHayParticiones() throws Exception {
        // Es el caso que ya vivimos: la red de la cátedra caída. La librería de Kafka escribía en WARN, en cada reintento de
        // conexión, "[Consumer clientId=..., groupId=<grupo>] Connection to node -1 (<servidor>) could not be established".
        try (var log = new LogCapture()) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(log.eventos("kafka.consumidor.sin_particiones")).isNotEmpty());
            Thread.sleep(8000); // varios reintentos de conexión de la librería

            String conNivelDeProduccion = log.list().stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
                    .filter(e -> !e.getThreadName().equals(Thread.currentThread().getName()))
                    .map(e -> e.getLoggerName() + " " + e.getFormattedMessage()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(conNivelDeProduccion).contains("kafka.consumidor.sin_particiones")
                    .doesNotContain("alumnos-prueba-inaccesible").doesNotContain("cuenta-de-prueba")
                    .doesNotContain("localhost:1").doesNotContain("127.0.0.1");
            assertThat(log.eventos("kafka.consumidor.sin_particiones").get(0).getFormattedMessage())
                    .contains("accion=cronos_sigue_atendiendo_pedidos_y_el_consumidor_reintenta_solo");
        }
    }
}
