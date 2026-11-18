package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 质量审查条目：数据集编译中发现的"无法归并 / 可能重复 / 待定"问题。
 * 质量审查阶段独立持有状态，问题需要人工 ACK 或 DISMISS 才能在报告中关闭。
 */
@Entity
@Table(name = "visit_quality_finding")
public class VisitQualityFinding {

    public enum Category {
        /** 身份尚未确定，记录无法归并 */
        UNRESOLVED_IDENTITY,
        /** 时间戳只有日期，无法定位到具体事件 */
        DATE_ONLY_TIME,
        /** 同一来源流水重复登记 */
        SAME_SOURCE_FLOW_DUPLICATE,
        /** 同源同业务指纹重复回传（如检验重复回传） */
        DUPLICATE_RECORD,
        /** 转诊单晚到，只能进入待定类别 */
        LATE_REFERRAL,
        /** 跨院相邻事件缺少转诊凭据，可能重复或转院 */
        UNVERIFIED_TRANSFER,
        /** 记录仅靠时间窗并入事件（无凭据） */
        TIME_WINDOW_MERGE,
        /** 同日同院存在多个事件，记录无法判断属于哪一次就诊 */
        AMBIGUOUS_MEMBERSHIP,
        /** 缺少凭据、仅靠时间窗聚合的待定事件 */
        PROBABLE_EVENT,
        /** 同一就诊凭据跨院出现，需核对 */
        CROSS_CAMPUS_WITHIN_ENCOUNTER,
        /** 同一来源记录号同内容重复回传 */
        EXACT_REUPLOAD
    }

    public enum Severity {INFO, WARNING, BLOCKER}

    public enum Status {PENDING, ACKED, DISMISSED}

    @Id
    public UUID id = UUID.randomUUID();

    @Column(nullable = false)
    public UUID datasetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    public Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    public Status status = Status.PENDING;

    public UUID recordId;

    public UUID eventId;

    public UUID pathwayId;

    @Column(length = 32)
    public String sourceSystem;

    @Column(length = 64)
    public String sourceRecordId;

    @Column(length = 32)
    public String campusCode;

    @Column(length = 700)
    public String detail;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant ackedAt;

    @Column(length = 64)
    public String ackedBy;

    @Column(length = 500)
    public String ackNote;
}
