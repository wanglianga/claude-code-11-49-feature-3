package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitSourceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitSourceRecordRepo extends JpaRepository<VisitSourceRecord, UUID> {

    Optional<VisitSourceRecord> findBySourceSystemAndSourceRecordId(String sourceSystem, String sourceRecordId);

    List<VisitSourceRecord> findAllByOrderByUpdatedAtDesc();

    @Query("select r from VisitSourceRecord r where r.sourceSystem = :sourceSystem " +
            "and r.sourcePatientId = :sourcePatientId")
    List<VisitSourceRecord> findBySource(String sourceSystem, String sourcePatientId);
}
