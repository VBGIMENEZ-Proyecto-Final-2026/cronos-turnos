package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuración del cliente REST del servicio central de la cátedra. {@code CATEDRA_REST_BASE_URL} y
 * {@code CATEDRA_ID_TOKEN} son obligatorias: si falta alguna, la aplicación no arranca y el mensaje dice cuál. El
 * token nunca aparece en {@code toString}. Son las mismas variables que usa atlas-catalogo.
 */
@ConfigurationProperties("catedra.rest")
public record CatedraProperties(
        String baseUrl,
        String idToken,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("1") long maxRetries) {

    public CatedraProperties {
        baseUrl = HttpClientSupport.requireHttpUrl(baseUrl, "CATEDRA_REST_BASE_URL (URL base de la API de la cátedra)");
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("Falta CATEDRA_ID_TOKEN (JWT técnico de la cátedra)");
        }
        HttpClientSupport.requireSaneTimeouts("catedra.rest", connectTimeout, readTimeout, maxRetries);
    }

    @Override
    public String toString() {
        return "CatedraProperties[baseUrl=" + baseUrl + ", idToken=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", maxRetries=" + maxRetries + "]";
    }
}
