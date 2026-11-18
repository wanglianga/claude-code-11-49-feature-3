package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 业务快照：预约 / 结算 / 检验 / 分析在运行启动时固定引用的图谱版本。
 * PUBLISHED 快照下游可用；CANDIDATE 快照是身份变化后生成、待业务确认的"候选快照"，
 * 未确认前下游不可读取（尚在审核）。
 */
@Entity
@Table(name = "snapshot")
public class Snapshot {

    public enum BusinessType {
        APPOINTMENT,
        SETTLEMENT,
        LAB,
        ANALYTICS,
        /** 就诊事件数据集：路径分析引用的身份快照 */
        VISIT
    }

    public enum Status {PUBLISHED, CANDIDATE, REJECTED, SUPERSEDED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public BusinessType businessType;

    /** 固定的图谱版本 */
    @Column(nullable = false)
    public long graphVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    @Column(length = 200)
    public String label;

    @Column(nullable = false, length = 64)
    public String createdBy;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(length = 64)
    public String confirmedBy;

    public Instant confirmedAt;

    /** 若由某次决议的影响分析生成，记录决议 ID */
    public UUID originResolutionId;

    @Column(length = 500)
    public String rejectReason;
}
