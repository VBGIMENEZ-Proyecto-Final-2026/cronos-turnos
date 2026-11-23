package ar.edu.um.cronos;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lo que necesita cualquier prueba que levante el contexto completo: el PostgreSQL de Testcontainers y la clave
 * JWT de prueba (generada al vuelo, ver {@link TestJwtKeyInitializer}). Todas comparten la misma configuración,
 * así que Spring reutiliza un solo contexto.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(TestcontainersConfiguration.class)
@ContextConfiguration(initializers = TestJwtKeyInitializer.class)
public @interface IntegrationTestSupport {
}
