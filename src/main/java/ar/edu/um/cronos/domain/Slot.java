package ar.edu.um.cronos.domain;

import java.time.LocalDate;
import java.time.LocalTime;

/** Un turno ofrecido: el día y de qué hora a qué hora. */
public record Slot(LocalDate date, LocalTime startTime, LocalTime endTime) {
}
