package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 检验复查间隔：同一主体、同一检验项目在两次确定性就诊之间的间隔。
 * 重复回传的检验记录已在来源去重阶段排除，不会缩短或重复计算间隔。
 */
@Entity
@Table(name = "visit_lab_interval")
public class VisitLabInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID datasetId;

    @Column(nullable = false)
    public UUID masterId;

    @Column(nullable = false, length = 128)
    public String testName;

    @Column(nullable = false)
    public UUID earlierEventId;

    @Column(nullable = false)
    public UUID laterEventId;

    @Column(length = 32)
    public String earlierCampus;

    @Column(length = 32)
    public String laterCampus;

    public Instant earlierAt;

    public Instant laterAt;

    @Column(nullable = false)
    public double intervalHours;

    @Column(nullable = false)
    public boolean crossCampus;
}
