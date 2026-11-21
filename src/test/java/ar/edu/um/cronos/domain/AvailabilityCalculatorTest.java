package ar.edu.um.cronos.domain;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La grilla de turnos y la resta de ocupaciones, sin Spring ni red. 2026-11-23 es lunes.
 */
class AvailabilityCalculatorTest {

    private static final LocalDate LUNES = LocalDate.of(2026, 11, 23);
    private static final LocalDate MARTES = LUNES.plusDays(1);
    private static final LocalDate MIERCOLES = LUNES.plusDays(2);
    /** Un momento bien anterior a todo, para los casos que no hablan de turnos pasados. */
    private static final LocalDateTime ANTES = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static WeeklySchedule horario(DayOfWeek dia, String desde, String hasta, int minutos, boolean habilitado) {
        return new WeeklySchedule(dia, LocalTime.parse(desde), LocalTime.parse(hasta), minutos, habilitado);
    }

    private static ProfessionalAgenda agenda(WeeklySchedule... horarios) {
        return new ProfessionalAgenda(15, true, List.of(horarios));
    }

    private static List<String> horas(List<Slot> turnos) {
        return turnos.stream().map(t -> t.date() + " " + t.startTime()).toList();
    }

    private static Occupancy ocupado(LocalDate fecha, String hora, OccupancyStatus estado) {
        return new Occupancy(fecha, LocalTime.parse(hora), estado, null);
    }

    // ---- la grilla -----------------------------------------------------------------------------------------

    @Test
    void laGrillaVaDeInicioAFinCadaDuracionDelTurno() {
        var turnos = AvailabilityCalculator.grid(
                agenda(horario(DayOfWeek.MONDAY, "09:00", "10:30", 30, true)), LUNES, LUNES);

        assertThat(turnos).containsExactly(
                new Slot(LUNES, LocalTime.of(9, 0), LocalTime.of(9, 30)),
                new Slot(LUNES, LocalTime.of(9, 30), LocalTime.of(10, 0)),
                new Slot(LUNES, LocalTime.of(10, 0), LocalTime.of(10, 30)));
    }

    @Test
    void unTurnoQueNoEntraCompletoNoExiste() {
        var turnos = AvailabilityCalculator.grid(
                agenda(horario(DayOfWeek.MONDAY, "09:00", "10:20", 30, true)), LUNES, LUNES);

        assertThat(horas(turnos)).containsExactly("2026-11-23 09:00", "2026-11-23 09:30");
    }

    @Test
    void unaDuracionMayorQueElHorarioNoDaTurnos() {
        assertThat(AvailabilityCalculator.grid(
                agenda(horario(DayOfWeek.MONDAY, "09:00", "09:30", 60, true)), LUNES, LUNES)).isEmpty();
    }

    @Test
    void unTurnoQueTerminaJustoAlCerrarEntra() {
        var turnos = AvailabilityCalculator.grid(
                agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true)), LUNES, LUNES);

        assertThat(turnos).hasSize(2);
        assertThat(turnos.get(1).endTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void soloSeOfreceElDiaDeLaSemanaDelHorario() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));

        assertThat(AvailabilityCalculator.grid(a, MARTES, MARTES)).isEmpty();
        assertThat(AvailabilityCalculator.grid(a, LUNES.plusDays(7), LUNES.plusDays(7))).hasSize(2);
    }

    @Test
    void unRangoDeVariosDiasUsaCadaHorarioEnSuDia() {
        var a = agenda(
                horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true),
                horario(DayOfWeek.WEDNESDAY, "15:00", "16:00", 60, true));

        var turnos = AvailabilityCalculator.grid(a, LUNES, LUNES.plusDays(6));

        assertThat(horas(turnos)).containsExactly("2026-11-23 09:00", "2026-11-23 09:30", "2026-11-25 15:00");
    }

    @Test
    void unHorarioDeshabilitadoNoDaTurnos() {
        var a = agenda(
                horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, false),
                horario(DayOfWeek.MONDAY, "15:00", "16:00", 30, true));

        assertThat(horas(AvailabilityCalculator.grid(a, LUNES, LUNES)))
                .containsExactly("2026-11-23 15:00", "2026-11-23 15:30");
    }

    @Test
    void unProfesionalDeshabilitadoNoOfreceTurnos() {
        var a = new ProfessionalAgenda(15, false, List.of(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true)));

        assertThat(AvailabilityCalculator.grid(a, LUNES, LUNES)).isEmpty();
        assertThat(AvailabilityCalculator.free(a, List.of(), LUNES, LUNES, ANTES)).isEmpty();
    }

    @Test
    void sinHorariosNoHayTurnos() {
        assertThat(AvailabilityCalculator.grid(agenda(), LUNES, LUNES.plusDays(30))).isEmpty();
    }

    @Test
    void unaDuracionNoPositivaSeIgnoraEnLugarDeColgarse() {
        var a = agenda(
                horario(DayOfWeek.MONDAY, "09:00", "10:00", 0, true),
                horario(DayOfWeek.MONDAY, "11:00", "12:00", -30, true));

        assertThat(AvailabilityCalculator.grid(a, LUNES, LUNES)).isEmpty();
    }

    @Test
    void unHorarioQueTerminaAntesDeEmpezarNoDaTurnos() {
        assertThat(AvailabilityCalculator.grid(
                agenda(horario(DayOfWeek.MONDAY, "12:00", "09:00", 30, true)), LUNES, LUNES)).isEmpty();
    }

    @Test
    void losHorariosSuperpuestosNoRepitenElMismoTurno() {
        var a = agenda(
                horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true),
                horario(DayOfWeek.MONDAY, "09:30", "11:00", 30, true));

        assertThat(horas(AvailabilityCalculator.grid(a, LUNES, LUNES)))
                .containsExactly("2026-11-23 09:00", "2026-11-23 09:30", "2026-11-23 10:00", "2026-11-23 10:30");
    }

    @Test
    void losTurnosSalenOrdenadosAunqueLosHorariosVenganDesordenados() {
        var a = agenda(
                horario(DayOfWeek.WEDNESDAY, "15:00", "15:30", 30, true),
                horario(DayOfWeek.MONDAY, "18:00", "18:30", 30, true),
                horario(DayOfWeek.MONDAY, "08:00", "08:30", 30, true));

        assertThat(horas(AvailabilityCalculator.grid(a, LUNES, MIERCOLES)))
                .containsExactly("2026-11-23 08:00", "2026-11-23 18:00", "2026-11-25 15:00");
    }

    // ---- las ocupaciones -----------------------------------------------------------------------------------

    @Test
    void losTurnosConfirmadosYBloqueadosNoSeOfrecen() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "11:00", 30, true));
        var ocupaciones = List.of(
                ocupado(LUNES, "09:00", OccupancyStatus.CONFIRMED),
                ocupado(LUNES, "10:00", OccupancyStatus.HELD));

        assertThat(horas(AvailabilityCalculator.free(a, ocupaciones, LUNES, LUNES, ANTES)))
                .containsExactly("2026-11-23 09:30", "2026-11-23 10:30");
    }

    @Test
    void unaOcupacionDeOtroDiaNoRestaNada() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));
        var ocupaciones = List.of(ocupado(LUNES.plusDays(7), "09:00", OccupancyStatus.CONFIRMED));

        assertThat(AvailabilityCalculator.free(a, ocupaciones, LUNES, LUNES, ANTES)).hasSize(2);
    }

    @Test
    void unaOcupacionQueNoCoincideConLaGrillaNoRestaNada() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));
        var ocupaciones = List.of(ocupado(LUNES, "09:15", OccupancyStatus.CONFIRMED));

        assertThat(AvailabilityCalculator.free(a, ocupaciones, LUNES, LUNES, ANTES)).hasSize(2);
    }

    @Test
    void siTodoEstaOcupadoNoQuedanTurnos() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));
        var ocupaciones = List.of(
                ocupado(LUNES, "09:00", OccupancyStatus.CONFIRMED),
                ocupado(LUNES, "09:30", OccupancyStatus.HELD));

        assertThat(AvailabilityCalculator.free(a, ocupaciones, LUNES, LUNES, ANTES)).isEmpty();
    }

    @Test
    void laMismaOcupacionRepetidaNoRompeNada() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));
        var ocupaciones = List.of(
                ocupado(LUNES, "09:00", OccupancyStatus.CONFIRMED),
                ocupado(LUNES, "09:00", OccupancyStatus.HELD));

        assertThat(horas(AvailabilityCalculator.free(a, ocupaciones, LUNES, LUNES, ANTES)))
                .containsExactly("2026-11-23 09:30");
    }

    // ---- los turnos que ya empezaron -----------------------------------------------------------------------

    @Test
    void unTurnoQueYaEmpezoNoSeOfrece() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "11:00", 30, true));
        var ahora = LocalDateTime.of(LUNES, LocalTime.of(9, 45));

        assertThat(horas(AvailabilityCalculator.free(a, List.of(), LUNES, LUNES, ahora)))
                .containsExactly("2026-11-23 10:00", "2026-11-23 10:30");
    }

    @Test
    void unTurnoQueEmpiezaJustoAhoraYaEmpezo() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));
        var ahora = LocalDateTime.of(LUNES, LocalTime.of(9, 30));

        assertThat(AvailabilityCalculator.free(a, List.of(), LUNES, LUNES, ahora)).isEmpty();
    }

    @Test
    void unDiaPasadoNoTieneTurnosYUnoFuturoSi() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));
        var ahora = LocalDateTime.of(LUNES.plusDays(1), LocalTime.NOON);

        assertThat(AvailabilityCalculator.free(a, List.of(), LUNES, LUNES, ahora)).isEmpty();
        assertThat(AvailabilityCalculator.free(a, List.of(), LUNES, LUNES.plusDays(7), ahora)).hasSize(2);
    }

    @Test
    void laGrillaNoDescartaLosTurnosPasadosNiRestaOcupaciones() {
        var a = agenda(horario(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));

        assertThat(AvailabilityCalculator.grid(a, LUNES.minusDays(30), LUNES)).hasSize(10);
    }
}
