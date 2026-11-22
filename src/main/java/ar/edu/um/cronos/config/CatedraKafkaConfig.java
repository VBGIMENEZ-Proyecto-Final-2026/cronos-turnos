package ar.edu.um.cronos.config;

import ar.edu.um.cronos.adapters.in.kafka.ReservationEventParser;
import ar.edu.um.cronos.adapters.in.kafka.ReservationEventsListener;
import ar.edu.um.cronos.adapters.in.kafka.ResilientConsumerLifecycle;
import ar.edu.um.cronos.adapters.in.kafka.ConsumerErrorPolicy;
import ar.edu.um.cronos.application.reservation.ProcessReservationEvent;
import ar.edu.um.cronos.logging.LogEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Arma el consumidor de los eventos de reserva de la cátedra: un contenedor de un solo hilo (el topic tiene una partición,
 * así que el orden de los eventos de un proceso está garantizado), con confirmación manual, un mensaje por vez y el manejador
 * de errores de {@link ConsumerErrorPolicy}. No usa {@code spring.kafka.*}: las propiedades son las propias de
 * {@link CatedraKafkaProperties}. Solo existe si {@code catedra.kafka.consumer.enabled} es verdadero (por defecto sí); los
 * tests de contexto lo apagan.
 *
 * <p><b>La aplicación arranca aunque Kafka no responda</b>: si los servidores existen pero no contestan, el cliente se
 * conecta en segundo plano; si ni siquiera se resuelven, {@link ResilientConsumerLifecycle} reintenta arrancar el contenedor.
 * El consumo se reanuda solo cuando Kafka vuelve.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "catedra.kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CatedraKafkaProperties.class)
class CatedraKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(CatedraKafkaConfig.class);

    /** Identificador del cliente ante el broker. No contiene el grupo ni nada de la cuenta. */
    static final String CLIENT_ID = "cronos-eventos-de-reserva";

    /** Aplicar un mensaje es una transacción corta; este margen cubre un reintento largo sin que Kafka lo dé por muerto. */
    private static final Duration MAX_POLL_INTERVAL = Duration.ofMinutes(10);

    @Bean
    ConsumerFactory<String, String> reservationEventsConsumerFactory(CatedraKafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(consumerConfig(properties));
    }

    static Map<String, Object> consumerConfig(CatedraKafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers().strip());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.effectiveGroupId());
        // El valor se lee como texto y se interpreta en el adaptador; ErrorHandlingDeserializer evita que un mensaje que
        // no se pueda deserializar trabe el consumo (llega como valor nulo y el intérprete lo rechaza).
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Un grupo sin offset confirmado lee todo lo que hay: nunca se pierde un evento (los de procesos que ya no existen
        // se estacionan). Con el offset confirmado, retoma desde ahí.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, properties.autoOffsetReset());
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) MAX_POLL_INTERVAL.toMillis());
        // Nunca crear el topic de la cátedra desde un consumidor.
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return config;
    }

    @Bean
    ReservationEventParser reservationEventParser(JsonMapper mapper) {
        return new ReservationEventParser(mapper);
    }

    @Bean
    KafkaMessageListenerContainer<String, String> reservationEventsContainer(
            ConsumerFactory<String, String> reservationEventsConsumerFactory, CatedraKafkaProperties properties,
            ReservationEventParser parser, ProcessReservationEvent process) {
        ContainerProperties container = new ContainerProperties(properties.actionsTopic().strip());
        // MANUAL_IMMEDIATE: el offset se confirma en el momento del acknowledge, después de la transacción.
        container.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // El client.id por defecto lleva el nombre del grupo (que contiene el groupId de la cuenta) y aparece en los logs del
        // cliente, en sus métricas y en los del broker: se fija uno propio.
        container.setClientId(CLIENT_ID);
        container.setMessageListener(new ReservationEventsListener(parser, process));
        container.setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                LogEvent.info(log, "kafka.consumidor.particiones_asignadas", "cantidad", partitions.size());
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                LogEvent.info(log, "kafka.consumidor.particiones_revocadas", "cantidad", partitions.size());
            }
        });

        var listener = new KafkaMessageListenerContainer<>(reservationEventsConsumerFactory, container);
        listener.setCommonErrorHandler(new ConsumerErrorPolicy(properties.retry(), parser, process).handler());
        // Lo arranca ResilientConsumerLifecycle, para que una falla al construir el consumidor no tumbe la app.
        listener.setAutoStartup(false);
        return listener;
    }

    @Bean
    ResilientConsumerLifecycle reservationEventsConsumerLifecycle(
            KafkaMessageListenerContainer<String, String> reservationEventsContainer, CatedraKafkaProperties properties) {
        return new ResilientConsumerLifecycle(reservationEventsContainer, properties.startRetryInterval());
    }
}
