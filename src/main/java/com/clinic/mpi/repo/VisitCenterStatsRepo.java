package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitCenterStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitCenterStatsRepo extends JpaRepository<VisitCenterStats, UUID> {

    List<VisitCenterStats> findByDatasetIdOrderByCampusCode(UUID datasetId);
}
