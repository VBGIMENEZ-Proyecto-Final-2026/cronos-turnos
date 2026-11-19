package ar.edu.um.cronos;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Atajo para que las pruebas de otros paquetes usen el PostgreSQL de Testcontainers (la configuración es del paquete). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(TestcontainersConfiguration.class)
public @interface TestcontainersConfigurationImport {
}
