package ar.edu.um.cronos;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Reglas de dependencia, las mismas que en atlas (ADR 0003): el dominio no conoce frameworks ni capas
 * externas y la aplicación no conoce adaptadores. Los dos paquetes ya tienen clases (el usuario, los casos de uso
 * y los puertos), así que las reglas no permiten paquetes vacíos: si alguien los vaciara o los renombrara, la
 * regla fallaría en lugar de pasar sin mirar nada.
 */
@AnalyzeClasses(packages = "ar.edu.um.cronos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule dominioSinFrameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapters..", "..config..", "org.springframework..");

    @ArchTest
    static final ArchRule aplicacionSinAdaptadores = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapters..", "..config..", "org.springframework.web..", "org.springframework.security..",
                    "org.springframework.kafka..", "tools.jackson..", "com.fasterxml.jackson..");

    /**
     * Norma de logs (ADR 0006): los hitos y las fallas se escriben con {@code LogEvent}, que fija la forma de la línea y
     * escapa los valores. {@code debug} queda libre para el detalle técnico.
     */
    @ArchTest
    static final ArchRule logsConLogEvent = noClasses()
            .that().resideOutsideOfPackage("..logging..")
            .should().callMethodWhere(target(nameMatching("info|warn|error"))
                    .and(target(owner(assignableTo(org.slf4j.Logger.class)))))
            .because("los eventos de info, warn y error se escriben con LogEvent (norma de logs, ADR 0006)");
}
