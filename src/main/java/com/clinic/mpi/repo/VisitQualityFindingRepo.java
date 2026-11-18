package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitQualityFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitQualityFindingRepo extends JpaRepository<VisitQualityFinding, UUID> {

    List<VisitQualityFinding> findByDatasetIdOrderByCreatedAt(UUID datasetId);

    List<VisitQualityFinding> findByDatasetIdAndStatus(UUID datasetId,
                                                       VisitQualityFinding.Status status);

    List<VisitQualityFinding> findByEventId(UUID eventId);

    long countByDatasetId(UUID datasetId);
}
