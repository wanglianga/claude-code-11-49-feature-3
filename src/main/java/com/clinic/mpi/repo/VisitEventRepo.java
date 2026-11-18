package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitEventRepo extends JpaRepository<VisitEvent, UUID> {

    List<VisitEvent> findByDatasetIdOrderByEventKey(UUID datasetId);

    List<VisitEvent> findByDatasetIdAndStatus(UUID datasetId, VisitEvent.Status status);

    List<VisitEvent> findByDatasetIdAndMasterIdAndStatusOrderByStartAt(
            UUID datasetId, UUID masterId, VisitEvent.Status status);

    long countByDatasetIdAndStatus(UUID datasetId, VisitEvent.Status status);
}
