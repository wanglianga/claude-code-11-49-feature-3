package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitEventMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VisitEventMemberRepo extends JpaRepository<VisitEventMember, UUID> {

    List<VisitEventMember> findByEventIdOrderByOrdinal(UUID eventId);

    List<VisitEventMember> findByDatasetId(UUID datasetId);
}
