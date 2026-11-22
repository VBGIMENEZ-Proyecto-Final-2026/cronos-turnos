package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.application.port.out.ProcessedEvents;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Para las pruebas del consumidor: reemplaza al registro de eventos procesados por uno que delega en el verdadero pero
 * puede fallar a pedido para un {@code eventId} (una caída pasajera de la base, o un mensaje que nunca se va a poder aplicar).
 * Vive en este paquete porque el registro verdadero es privado del paquete.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ProcessedEventsFalibleConfiguration {

    @Bean
    @Primary
    ProcessedEventsFalible processedEventsFalible(JdbcEventLog real) {
        return new ProcessedEventsFalible(real);
    }

    public static class ProcessedEventsFalible implements ProcessedEvents {

        private final ProcessedEvents real;
        private final Map<String, AtomicInteger> remaining = new ConcurrentHashMap<>();
        private final Map<String, Supplier<RuntimeException>> failure = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        ProcessedEventsFalible(ProcessedEvents real) {
            this.real = real;
        }

        /** Hace fallar las próximas {@code times} veces que se intente marcar ese evento ({@link Integer#MAX_VALUE}: siempre). */
        public void fail(String eventId, int times, Supplier<RuntimeException> error) {
            failure.put(eventId, error);
            remaining.put(eventId, new AtomicInteger(times));
        }

        public int calls(String eventId) {
            var n = calls.get(eventId);
            return n == null ? 0 : n.get();
        }

        public void reset() {
            remaining.clear();
            failure.clear();
            calls.clear();
        }

        @Override
        public boolean markProcessed(String eventId, String eventType, String reservationProcessId, Instant at) {
            calls.computeIfAbsent(eventId, k -> new AtomicInteger()).incrementAndGet();
            var left = remaining.get(eventId);
            if (left != null && left.get() > 0) {
                if (left.get() != Integer.MAX_VALUE) {
                    left.decrementAndGet();
                }
                throw failure.get(eventId).get();
            }
            return real.markProcessed(eventId, eventType, reservationProcessId, at);
        }
    }
}
