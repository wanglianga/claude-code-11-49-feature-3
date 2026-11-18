package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 就诊数据集版本号分配器（单行）：与图谱状态一样用悲观写锁串行化编译，
 * 保证同一次补传/快照修订只会产生一个确定的新版本号。
 */
@Entity
@Table(name = "visit_dataset_state")
public class VisitDatasetState {

    public static final int SINGLETON_ID = 1;

    @Id
    public int id = SINGLETON_ID;

    @Column(nullable = false)
    public long lastVersion = 0L;
}
