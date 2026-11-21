package ar.edu.um.cronos;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;

/**
 * Le da al contexto de prueba lo que cronos necesita para arrancar: la clave privada JWT y la configuración de la
 * cátedra (valores falsos: ninguna prueba llama a la red). Corre primero ({@code @Order(0)}): una prueba que
 * ejercita los clientes aporta su propio inicializador con un orden posterior y sus valores ganan.
 */
@Order(0)
public class TestJwtKeyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                "cronos.security.jwt.private-key=" + TestKeys.privatePem(),
                "catedra.rest.base-url=http://catedra.invalid",
                "catedra.rest.id-token=token-tecnico-de-prueba",
                "cronos.atlas.base-url=http://atlas.invalid").applyTo(context);
    }
}
