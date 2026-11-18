package com.clinic.mpi.repo;

import com.clinic.mpi.domain.VisitDatasetState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface VisitDatasetStateRepo extends JpaRepository<VisitDatasetState, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from VisitDatasetState s where s.id = 1")
    Optional<VisitDatasetState> lockSingleton();
}
