package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitLabInterval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitLabIntervalRepo extends JpaRepository<VisitLabInterval, UUID> {

    List<VisitLabInterval> findByDatasetId(UUID datasetId);

    List<VisitLabInterval> findByDatasetIdAndMasterId(UUID datasetId, UUID masterId);

    long countByDatasetId(UUID datasetId);

    long countByDatasetIdAndCrossCampus(UUID datasetId, boolean crossCampus);
}
