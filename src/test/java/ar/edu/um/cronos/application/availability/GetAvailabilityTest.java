package ar.edu.um.cronos.application.availability;

import ar.edu.um.cronos.application.port.out.CatalogClient;
import ar.edu.um.cronos.application.port.out.CatalogUnavailableException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.OccupancyClient;
import ar.edu.um.cronos.application.port.out.ProfessionalNotFoundException;
import ar.edu.um.cronos.domain.Occupancy;
import ar.edu.um.cronos.domain.OccupancyStatus;
import ar.edu.um.cronos.domain.ProfessionalAgenda;
import ar.edu.um.cronos.domain.WeeklySchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El caso de uso con dobles: qué consulta, en qué orden y qué hace cuando alguien falla. 2026-11-23 es lunes. */
class GetAvailabilityTest {

    private static final LocalDate LUNES = LocalDate.of(2026, 11, 23);
    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Domingo 22 a la noche en el consultorio: ningún turno del lunes empezó. */
    private static final Clock DOMINGO = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC);

    private final List<String> llamadas = new ArrayList<>();
    private ProfessionalAgenda agenda;
    private List<Occupancy> ocupaciones;
    private RuntimeException fallaDelCatalogo;
    private RuntimeException fallaDeLaCatedra;
    private String tokenRecibido;
    private LocalDate desdeRecibido;
    private LocalDate hastaRecibido;

    private final CatalogClient catalogo = (id, token) -> {
        llamadas.add("catalogo");
        tokenRecibido = token;
        if (fallaDelCatalogo != null) {
            throw fallaDelCatalogo;
        }
        return agenda;
    };

    private final OccupancyClient catedra = (id, desde, hasta) -> {
        llamadas.add("catedra");
        desdeRecibido = desde;
        hastaRecibido = hasta;
        if (fallaDeLaCatedra != null) {
            throw fallaDeLaCatedra;
        }
        return ocupaciones;
    };

    private GetAvailability casoDeUso(Clock reloj, ZoneId zona) {
        return new GetAvailability(catalogo, catedra, reloj, zona, 31);
    }

    @BeforeEach
    void preparar() {
        agenda = new ProfessionalAgenda(15, true, List.of(new WeeklySchedule(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(11, 0), 30, true)));
        ocupaciones = List.of();
    }

    @Test
    void devuelveLaGrillaMenosLasOcupaciones() {
        ocupaciones = List.of(
                new Occupancy(LUNES, LocalTime.of(9, 30), OccupancyStatus.CONFIRMED, null),
                new Occupancy(LUNES, LocalTime.of(10, 0), OccupancyStatus.HELD, Instant.parse("2026-11-23T12:00:00Z")));

        var resultado = casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt-del-usuario");

        assertThat(resultado.professionalId()).isEqualTo(15);
        assertThat(resultado.slots()).extracting(s -> s.startTime().toString())
                .containsExactly("09:00", "10:30");
    }

    @Test
    void primeroPideElCatalogoYDespuesLaCatedraUnaSolaVezParaTodoElRango() {
        casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES.plusDays(13), "jwt");

        assertThat(llamadas).containsExactly("catalogo", "catedra");
        assertThat(desdeRecibido).isEqualTo(LUNES);
        assertThat(hastaRecibido).isEqualTo(LUNES.plusDays(13));
    }

    @Test
    void alCatalogoSeLeReenviaElTokenDelUsuarioTalCual() {
        casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt-del-usuario");

        assertThat(tokenRecibido).isEqualTo("jwt-del-usuario");
    }

    @Test
    void unProfesionalDeshabilitadoNoOfreceTurnosNiMolestaALaCatedra() {
        agenda = new ProfessionalAgenda(15, false, agenda.schedules());

        var resultado = casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt");

        assertThat(resultado.slots()).isEmpty();
        assertThat(llamadas).containsExactly("catalogo");
    }

    @Test
    void siNoAtiendeEnElRangoNoSeConsultaALaCatedra() {
        var resultado = casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES.plusDays(1), LUNES.plusDays(2), "jwt");

        assertThat(resultado.slots()).isEmpty();
        assertThat(llamadas).containsExactly("catalogo");
    }

    @Test
    void unaListaVaciaDeOcupacionesEsValida() {
        var resultado = casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt");

        assertThat(resultado.slots()).hasSize(4);
    }

    @Test
    void siElCatalogoFallaNoSePreguntaALaCatedra() {
        fallaDelCatalogo = new CatalogUnavailableException("caído", null);

        assertThatThrownBy(() -> casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt"))
                .isSameAs(fallaDelCatalogo);
        assertThat(llamadas).containsExactly("catalogo");
    }

    @Test
    void unProfesionalInexistenteSePropagaSinPreguntarALaCatedra() {
        fallaDelCatalogo = new ProfessionalNotFoundException(15);

        assertThatThrownBy(() -> casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt"))
                .isInstanceOf(ProfessionalNotFoundException.class);
        assertThat(llamadas).containsExactly("catalogo");
    }

    @Test
    void siLaCatedraFallaSePropagaYNoSeInventanTurnosLibres() {
        fallaDeLaCatedra = new CatedraUnavailableException("caída", null);

        assertThatThrownBy(() -> casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt"))
                .isSameAs(fallaDeLaCatedra);
    }

    // ---- el rango ------------------------------------------------------------------------------------------

    @Test
    void unRangoInvertidoSeRechazaSinLlamarANadie() {
        assertThatThrownBy(() -> casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES.plusDays(1), LUNES, "jwt"))
                .isInstanceOf(InvalidDateRangeException.class);
        assertThat(llamadas).isEmpty();
    }

    @Test
    void unRangoDeUnSoloDiaEsValido() {
        assertThat(casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt").slots()).isNotEmpty();
    }

    @Test
    void elRangoMaximoEsValidoYUnDiaMasSeRechaza() {
        casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES.plusDays(30), "jwt");

        assertThatThrownBy(() -> casoDeUso(DOMINGO, BUENOS_AIRES).execute(15, LUNES, LUNES.plusDays(31), "jwt"))
                .isInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void unMaximoDeRangoInvalidoNoSeAcepta() {
        assertThatThrownBy(() -> new GetAvailability(catalogo, catedra, DOMINGO, BUENOS_AIRES, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- la zona del consultorio ---------------------------------------------------------------------------

    @Test
    void losTurnosQueYaEmpezaronSeDecidenEnLaHoraLocalDelConsultorio() {
        // 12:30 UTC son las 09:30 en Buenos Aires: el turno de las 09:00 ya empezó y el de las 09:30 también.
        Clock mediodia = Clock.fixed(Instant.parse("2026-11-23T12:30:00Z"), ZoneOffset.UTC);

        var enBuenosAires = casoDeUso(mediodia, BUENOS_AIRES).execute(15, LUNES, LUNES, "jwt");
        var enUtc = casoDeUso(mediodia, ZoneOffset.UTC).execute(15, LUNES, LUNES, "jwt");

        assertThat(enBuenosAires.slots()).extracting(s -> s.startTime().toString()).containsExactly("10:00", "10:30");
        // Con la zona equivocada (UTC) ya serían las 12:30 y no quedaría nada: la zona importa.
        assertThat(enUtc.slots()).isEmpty();
    }
}
