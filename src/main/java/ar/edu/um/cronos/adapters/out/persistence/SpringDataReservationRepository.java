package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.domain.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Optional;

interface SpringDataReservationRepository extends JpaRepository<ReservationEntity, Long> {

    Optional<ReservationEntity> findByReservationProcessId(String reservationProcessId);

    Page<ReservationEntity> findByUserId(long userId, Pageable pageable);

    Page<ReservationEntity> findByUserIdAndStatus(long userId, ReservationStatus status, Pageable pageable);

    Optional<ReservationEntity> findFirstByUserIdAndProfessionalIdAndDateAndStartTimeAndStatusIn(
            long userId, long professionalId, LocalDate date, LocalTime startTime,
            Collection<ReservationStatus> statuses);
}
