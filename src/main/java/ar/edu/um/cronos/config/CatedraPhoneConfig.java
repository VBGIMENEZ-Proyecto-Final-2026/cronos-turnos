package ar.edu.um.cronos.config;

import ar.edu.um.cronos.adapters.out.kafka.DisabledPhoneGateway;
import ar.edu.um.cronos.adapters.out.kafka.KafkaPhoneGateway;
import ar.edu.um.cronos.application.port.out.PhoneGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * El envío del teléfono a la cátedra. Encendido por defecto: exige {@link CatedraPhoneProperties}. Con
 * {@code catedra.phone.enabled=false} (lo que usan las pruebas de contexto) no se exige nada de Kafka y el endpoint del
 * teléfono responde {@code 503}.
 */
@Configuration(proxyBeanMethods = false)
class CatedraPhoneConfig {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "catedra.phone.enabled", havingValue = "true", matchIfMissing = true)
    @EnableConfigurationProperties(CatedraPhoneProperties.class)
    static class Enabled {

        @Bean(destroyMethod = "close")
        PhoneGateway phoneGateway(CatedraPhoneProperties properties, JsonMapper mapper) {
            return new KafkaPhoneGateway(properties, mapper);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "catedra.phone.enabled", havingValue = "false")
    static class Disabled {

        @Bean
        PhoneGateway phoneGateway() {
            return new DisabledPhoneGateway();
        }
    }
}
