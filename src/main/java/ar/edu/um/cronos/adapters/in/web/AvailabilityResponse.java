package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.application.availability.Availability;
import ar.edu.um.cronos.domain.Slot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Los turnos libres de un profesional en un rango de fechas (contrato de cronos, disponibilidad). */
record AvailabilityResponse(long professionalId, LocalDate from, LocalDate to, List<SlotResponse> slots) {

    static AvailabilityResponse from(Availability availability) {
        return new AvailabilityResponse(availability.professionalId(), availability.from(), availability.to(),
                availability.slots().stream().map(SlotResponse::from).toList());
    }

    record SlotResponse(LocalDate date, LocalTime startTime, LocalTime endTime) {

        static SlotResponse from(Slot slot) {
            return new SlotResponse(slot.date(), slot.startTime(), slot.endTime());
        }
    }
}
