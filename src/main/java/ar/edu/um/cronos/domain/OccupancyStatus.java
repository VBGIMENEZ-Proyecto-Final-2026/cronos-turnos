package ar.edu.um.cronos.domain;

/** Por qué un turno no está disponible según la cátedra: reservado ({@code CONFIRMED}) o bloqueado ({@code HELD}). */
public enum OccupancyStatus {
    CONFIRMED,
    HELD
}
