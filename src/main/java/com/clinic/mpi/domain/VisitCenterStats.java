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
 * 各业务中心（院区 / 采血 / 影像中心）维度的路径统计。
 *
 * <p>复诊以"诊疗过程（episode）"计数，不以来源记录或事件计数：
 * 一次就诊内的挂号、签到、检验、处方记录再多也只是一次；跨院转诊合并为一个
 * episode 也只算一次。因此各中心复诊率不会因记录粒度不同被虚假放大。</p>
 */
@Entity
@Table(name = "visit_center_stats")
public class VisitCenterStats {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID datasetId;

    @Column(nullable = false, length = 32)
    public String campusCode;

    @Column(nullable = false)
    public int recordCount;
    @Column(nullable = false)
    public int confirmedEventCount;
    @Column(nullable = false)
    public int probableEventCount;
    @Column(nullable = false)
    public int duplicateRecordCount;
    @Column(nullable = false)
    public int ambiguousRecordCount;
    @Column(nullable = false)
    public int unmergeableRecordCount;

    /** 在该中心有确定事件的去重主体数 */
    @Column(nullable = false)
    public int patients;
    @Column(nullable = false)
    public int episodes;
    /** 在该中心有 ≥2 个独立 episode 的主体数 */
    @Column(nullable = false)
    public int patientsWithRecurrence;
    /** 各主体 max(0, 该中心 episode 数 - 1) 之和 */
    @Column(nullable = false)
    public int recurrenceCount;
    /** patientsWithRecurrence / patients */
    @Column(nullable = false)
    public double recurrenceRate;
    @Column(nullable = false)
    public int crossCampusEpisodeCount;
    @Column(nullable = false)
    public int pendingReviewEventCount;
    @Column(nullable = false)
    public int labIntervalCount;
}
