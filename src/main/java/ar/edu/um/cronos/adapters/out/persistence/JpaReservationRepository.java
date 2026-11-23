package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.application.port.out.ActiveReservationExistsException;
import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import ar.edu.um.cronos.application.port.out.ReservationPage;
import ar.edu.um.cronos.application.port.out.ReservationRepository;
import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
    public Optional<Reservation> findByReservationProcessId(String reservationProcessId) {
        return repository.findByReservationProcessId(reservationProcessId).map(ReservationEntity::toDomain);
    }

    @Override
    public ReservationPage findByUser(long userId, ReservationStatus status, int page, int size) {
        // Lo más reciente primero; a igual fecha, el id de mayor número (es el orden en que se crearon).
        var order = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var found = status == null ? repository.findByUserId(userId, order)
                : repository.findByUserIdAndStatus(userId, status, order);
        return new ReservationPage(found.getContent().stream().map(ReservationEntity::toDomain).toList(),
                found.getTotalElements(), page, size);
    }

    @Override
    public Optional<Reservation> findActive(long userId, long professionalId, LocalDate date, LocalTime startTime) {
        return repository.findFirstByUserIdAndProfessionalIdAndDateAndStartTimeAndStatusIn(
                userId, professionalId, date, startTime, ACTIVE).map(ReservationEntity::toDomain);
    }

    @Override
    public List<Reservation> findExpiredPending(Instant cutoff, int limit) {
        return repository.findByStatusInAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(
                List.of(ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE), cutoff, PageRequest.of(0, limit))
                .stream().map(ReservationEntity::toDomain).toList();
    }

    @Override
    public List<Reservation> findStale(ReservationStatus status, Instant cutoff, int limit) {
        return repository.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAscIdAsc(
                status, cutoff, PageRequest.of(0, limit)).stream().map(ReservationEntity::toDomain).toList();
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
