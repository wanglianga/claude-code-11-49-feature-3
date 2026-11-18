package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitPathway;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitPathwayRepo extends JpaRepository<VisitPathway, UUID> {

    List<VisitPathway> findByDatasetId(UUID datasetId);

    List<VisitPathway> findByDatasetIdAndMasterId(UUID datasetId, UUID masterId);

    List<VisitPathway> findByDatasetIdAndStatus(UUID datasetId, VisitPathway.Status status);

    long countByDatasetIdAndStatus(UUID datasetId, VisitPathway.Status status);

    long countByDatasetIdAndType(UUID datasetId, VisitPathway.Type type);
}
