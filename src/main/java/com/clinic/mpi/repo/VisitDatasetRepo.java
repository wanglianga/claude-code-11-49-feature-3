package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitDataset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisitDatasetRepo extends JpaRepository<VisitDataset, UUID> {

    List<VisitDataset> findAllByOrderByDatasetVersionDesc();

    Optional<VisitDataset> findFirstByOrderByDatasetVersionDesc();

    Optional<VisitDataset> findBySnapshotId(UUID snapshotId);

    Optional<VisitDataset> findByDatasetVersion(long datasetVersion);

    Optional<VisitDataset> findByInputFingerprint(String inputFingerprint);

    @Query("select coalesce(max(d.datasetVersion), 0) from VisitDataset d")
    long maxVersion();
}
