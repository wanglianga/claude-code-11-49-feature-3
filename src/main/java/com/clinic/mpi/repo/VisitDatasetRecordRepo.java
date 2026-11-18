package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitDatasetRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitDatasetRecordRepo extends JpaRepository<VisitDatasetRecord, UUID> {

    List<VisitDatasetRecord> findByDatasetId(UUID datasetId);

    List<VisitDatasetRecord> findByDatasetIdAndMasterId(UUID datasetId, UUID masterId);

    List<VisitDatasetRecord> findByEventId(UUID eventId);
}
