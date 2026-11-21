package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Seguridad de cronos: cómo se firman y validan los JWT que emite y qué orígenes CORS se aceptan. Cronos tiene la
 * clave PRIVADA; atlas solo la pública (ADR 0004, opción A). Para validar sus propios tokens cronos deriva la
 * pública de la privada.
 */
@ConfigurationProperties("cronos.security")
public record SecurityProperties(@DefaultValue Jwt jwt, @DefaultValue Cors cors) {

    /**
     * @param privateKey         clave privada RSA en PEM PKCS#8 ({@code BEGIN PRIVATE KEY}), por ejemplo desde un
     *                           secreto de Docker; las secuencias {@code \n} literales se toman como saltos de línea
     * @param privateKeyLocation ruta o URL ({@code file:}, {@code classpath:}) de un archivo con esa clave; sin
     *                           esquema se toma como ruta del sistema de archivos
     * @param ttl                vigencia normal del token
     * @param rememberMeTtl      vigencia con {@code rememberMe}
     * @param clockSkew          tolerancia de reloj al validar {@code exp} y {@code nbf} de los tokens recibidos
     */
    public record Jwt(
            String privateKey,
            String privateKeyLocation,
            @DefaultValue("1h") Duration ttl,
            @DefaultValue("30d") Duration rememberMeTtl,
            @DefaultValue("60s") Duration clockSkew) {

        public Jwt {
            privateKey = blankToNull(privateKey);
            privateKeyLocation = blankToNull(privateKeyLocation);
            if (privateKey == null && privateKeyLocation == null) {
                throw new IllegalArgumentException("Falta CRONOS_JWT_PRIVATE_KEY_LOCATION (archivo) o "
                        + "CRONOS_JWT_PRIVATE_KEY (PEM) con la clave privada con la que cronos firma los JWT");
            }
            if (privateKey != null && privateKeyLocation != null) {
                throw new IllegalArgumentException(
                        "Definí solo una: CRONOS_JWT_PRIVATE_KEY_LOCATION o CRONOS_JWT_PRIVATE_KEY, no las dos");
            }
            if (ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("CRONOS_JWT_TTL debe ser positiva");
            }
            if (rememberMeTtl.compareTo(ttl) < 0) {
                throw new IllegalArgumentException("CRONOS_JWT_REMEMBER_ME_TTL no puede ser menor que CRONOS_JWT_TTL");
            }
            if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("CRONOS_JWT_CLOCK_SKEW debe estar entre 0 y 5 minutos");
            }
        }

        /** No imprime la clave: este objeto puede terminar en un log de arranque. */
        @Override
        public String toString() {
            return "Jwt[privateKey=" + (privateKey == null ? "-" : "(definida)") + ", privateKeyLocation="
                    + privateKeyLocation + ", ttl=" + ttl + ", rememberMeTtl=" + rememberMeTtl + ", clockSkew=" + clockSkew + "]";
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /**
     * @param allowedOrigins orígenes permitidos, cada uno con esquema y sin comodines (por ejemplo
     *                       {@code https://app.ejemplo.test}); vacío significa que no se acepta ningún origen
     * @param maxAge         cuánto puede recordar el navegador la respuesta a una consulta previa
     */
    public record Cors(@DefaultValue List<String> allowedOrigins, @DefaultValue("1h") Duration maxAge) {

        private static final Pattern ORIGIN = Pattern.compile("^https?://[^\\s*/?#@]+$");

        public Cors {
            allowedOrigins = allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).toList();
            for (String origin : allowedOrigins) {
                if (!ORIGIN.matcher(origin).matches()) {
                    throw new IllegalArgumentException("CRONOS_CORS_ALLOWED_ORIGINS: cada origen debe ser "
                            + "esquema://host[:puerto], sin comodines ni rutas");
                }
            }
            if (maxAge.isNegative()) {
                throw new IllegalArgumentException("cronos.security.cors.max-age no puede ser negativo");
            }
        }
    }
}
