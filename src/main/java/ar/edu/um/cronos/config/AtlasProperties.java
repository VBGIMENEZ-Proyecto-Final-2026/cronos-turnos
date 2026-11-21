package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Dónde está el servicio de catálogo (atlas-catalogo) y cuánto se espera su respuesta. Cronos le reenvía el JWT
 * del usuario (ADR 0004), así que no hay credencial propia para atlas.
 */
@ConfigurationProperties("cronos.atlas")
public record AtlasProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("1") long maxRetries) {

    public AtlasProperties {
        baseUrl = HttpClientSupport.requireHttpUrl(baseUrl, "ATLAS_BASE_URL (URL base de atlas-catalogo)");
        HttpClientSupport.requireSaneTimeouts("cronos.atlas", connectTimeout, readTimeout, maxRetries);
    }
}
