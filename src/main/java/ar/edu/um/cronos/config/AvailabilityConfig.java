package ar.edu.um.cronos.config;

import ar.edu.um.cronos.adapters.out.rest.AtlasCatalogClient;
import ar.edu.um.cronos.adapters.out.rest.CatedraOccupancyClient;
import ar.edu.um.cronos.adapters.out.rest.RestFailures;
import ar.edu.um.cronos.application.availability.GetAvailability;
import ar.edu.um.cronos.application.port.out.CatalogClient;
import ar.edu.um.cronos.application.port.out.OccupancyClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.time.Clock;

/**
 * Cablea la disponibilidad: los clientes de atlas y de la cátedra y el caso de uso. Si falta la URL o el token de la
 * cátedra la aplicación no arranca (mejor que fallar en el primer pedido).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AtlasProperties.class, CatedraProperties.class, AvailabilityProperties.class})
class AvailabilityConfig {

    @Bean
    CatalogClient catalogClient(AtlasProperties atlas) {
        return new AtlasCatalogClient(
                HttpClientSupport.restClient(atlas.baseUrl(), atlas.connectTimeout(), atlas.readTimeout()).build(),
                HttpClientSupport.retry(atlas.maxRetries(), RestFailures::isTransient));
    }

    @Bean
    OccupancyClient occupancyClient(CatedraProperties catedra) {
        return new CatedraOccupancyClient(
                HttpClientSupport.restClient(catedra.baseUrl(), catedra.connectTimeout(), catedra.readTimeout())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + catedra.idToken().strip())
                        .build(),
                HttpClientSupport.retry(catedra.maxRetries(), RestFailures::isTransient));
    }

    @Bean
    GetAvailability getAvailability(CatalogClient catalog, OccupancyClient occupancies, Clock clock,
                                    AvailabilityProperties properties) {
        return new GetAvailability(catalog, occupancies, clock, properties.zone(), properties.maxRangeDays());
    }
}
