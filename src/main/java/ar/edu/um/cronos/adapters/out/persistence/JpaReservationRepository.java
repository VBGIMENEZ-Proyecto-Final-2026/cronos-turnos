package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.application.port.out.ActiveReservationExistsException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Adaptador del puerto {@link ReservationRepository} sobre Spring Data JPA. */
@Component
class JpaReservationRepository implements ReservationRepository {

    private static final List<ReservationStatus> ACTIVE =
            Arrays.stream(ReservationStatus.values()).filter(ReservationStatus::occupiesSlot).toList();

    private final SpringDataReservationRepository repository;

    JpaReservationRepository(SpringDataReservationRepository repository) {
        this.repository = repository;
    }

    /**
     * Guarda el proceso y hace efectiva la escritura enseguida ({@code saveAndFlush}) para que una violación de la
     * base o un bloqueo optimista fallido salten acá y no más tarde. Traduce el índice único parcial de procesos
     * activos y el choque de versiones; cualquier otra violación (un CHECK, por ejemplo) sigue siendo una
     * {@link DataIntegrityViolationException}.
     */
    @Override
    public Reservation save(Reservation reservation) {
        try {
            return repository.saveAndFlush(ReservationEntity.from(reservation)).toDomain();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ReservationConflictException(e);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, "reservation_process_active_slot")) {
                throw new ActiveReservationExistsException(e);
            }
            throw e;
        }
    }

    @Override
    public Optional<Reservation> findById(long id) {
        return repository.findById(id).map(ReservationEntity::toDomain);
    }

    @Override
    public Optional<Reservation> findActive(long userId, long professionalId, LocalDate date, LocalTime startTime) {
        return repository.findFirstByUserIdAndProfessionalIdAndDateAndStartTimeAndStatusIn(
                userId, professionalId, date, startTime, ACTIVE).map(ReservationEntity::toDomain);
    }

    private static boolean violates(Throwable error, String constraint) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
