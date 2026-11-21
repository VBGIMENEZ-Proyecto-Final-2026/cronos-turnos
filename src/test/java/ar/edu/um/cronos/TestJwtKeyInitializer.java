package ar.edu.um.cronos;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Le da al contexto de prueba la clave privada JWT (cronos no arranca sin una). */
public class TestJwtKeyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of("cronos.security.jwt.private-key=" + TestKeys.privatePem()).applyTo(context);
    }
}
