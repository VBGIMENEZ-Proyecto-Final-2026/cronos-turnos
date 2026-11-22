package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.LogCapture;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El ciclo de vida reintenta el arranque del contenedor en segundo plano y nunca lanza hacia la aplicación. */
class ResilientConsumerLifecycleTest {

    @Test
    void siElContenedorFallaAlArrancarNoLanzaYReintentaHastaQueArranca() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doThrow(new KafkaException("Failed to construct kafka consumer")).doThrow(new KafkaException("otra vez"))
                .doNothing().when(container).start();
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofMillis(50));
        try (var log = new LogCapture()) {
            lifecycle.start(); // no lanza aunque la primera vez falle

            assertThat(lifecycle.isRunning()).isTrue();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(container, times(3)).start());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(log.eventos("kafka.consumidor.iniciado")).hasSize(1));
            assertThat(log.eventos("kafka.consumidor.no_inicia")).hasSize(2).allSatisfy(e ->
                    assertThat(e.getFormattedMessage()).contains("motivo=KafkaException")
                            .contains("accion=cronos_sigue_atendiendo_pedidos"));
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void noEscribeElMensajeDeLaExcepcionPorqueTrae_laDireccionDeLaCatedra() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doThrow(new KafkaException("Failed to resolve 'kafka-de-la-catedra.example:9092'"))
                .when(container).start();
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofSeconds(30));
        try (var log = new LogCapture()) {
            lifecycle.start();

            assertThat(log.eventos("kafka.consumidor.no_inicia")).hasSize(1);
            assertThat(log.text()).doesNotContain("kafka-de-la-catedra").doesNotContain("9092");
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void alDetenerseDejaDeReintentar() throws Exception {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doThrow(new KafkaException("falla")).when(container).start();
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofMillis(50));

        lifecycle.start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> verify(container, atLeast(2)).start());
        lifecycle.stop();
        Thread.sleep(200); // deja terminar un intento que estuviera en curso
        int alDetenerse = org.mockito.Mockito.mockingDetails(container).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("start")).toList().size();
        Thread.sleep(400);

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(org.mockito.Mockito.mockingDetails(container).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("start")).count()).isEqualTo(alDetenerse);
    }

    @Test
    void alDetenerseDetieneElContenedorSiEstabaCorriendo() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        doNothing().when(container).start();
        when(container.isRunning()).thenReturn(true);
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofSeconds(1));

        lifecycle.start();
        lifecycle.stop();

        verify(container).stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void siElContenedorNoEstabaCorriendoNoLoDetiene() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(false);
        var lifecycle = new ResilientConsumerLifecycle(container, Duration.ofSeconds(1));

        lifecycle.start();
        lifecycle.stop();

        verify(container, never()).stop();
    }
}
