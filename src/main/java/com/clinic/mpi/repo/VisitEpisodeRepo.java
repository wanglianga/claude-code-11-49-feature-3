package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitEpisode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitEpisodeRepo extends JpaRepository<VisitEpisode, UUID> {

    List<VisitEpisode> findByDatasetIdOrderByEpisodeKey(UUID datasetId);

    List<VisitEpisode> findByDatasetIdAndMasterId(UUID datasetId, UUID masterId);

    long countByDatasetId(UUID datasetId);
}
