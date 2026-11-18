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
 * 数据集内的来源记录快照：记录本次编译时该记录引用的身份 / 主体、
 * 去重结论、归并分类与所属事件。分类是记录级"结论"，只属于当前数据集版本。
 */
@Entity
@Table(name = "visit_dataset_record")
public class VisitDatasetRecord {

    public enum IdentityStatus {RESOLVED, UNRESOLVED}

    public enum DedupStatus {UNIQUE, POSSIBLE_DUPLICATE}

    public enum Classification {
        /** 归入确定性事件（凭据 / 转诊 / 服务链） */
        CONFIRMED_EVENT,
        /** 只靠时间窗聚成的事件，缺少凭据，不能计入确定复诊 */
        PROBABLE_EVENT,
        /** 只有日期级时间，无法定位到具体事件 */
        AMBIGUOUS_TIME_EVENT,
        /** 同院同日存在多个候选事件，凭据缺失，无法判断归属 */
        AMBIGUOUS_MEMBERSHIP,
        /** 事件本身确定，但因转诊单晚到或跨院缺凭据处于待定，不并入确定复诊也不删除 */
        PENDING_EVENT,
        /** 同源同业务指纹或同流水重复回传，已排除计数 */
        POSSIBLE_DUPLICATE,
        /** 身份尚未确定，无法归并 */
        UNRESOLVED_IDENTITY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID datasetId;

    @Column(nullable = false)
    public UUID recordId;

    public UUID identityId;

    public UUID masterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public IdentityStatus identityStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public DedupStatus dedupStatus = DedupStatus.UNIQUE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public Classification classification;

    public UUID eventId;

    /** CONFIRMED / PROBABLE_TIME_WINDOW / TIME_WINDOW_ONLY / DATE_ONLY / DEDUPED / NONE */
    @Column(length = 32)
    public String membership;

    @Column(length = 500)
    public String note;

    // ---- 冗余字段：便于按来源/院区直接出报告，避免统计时联表 ----
    @Column(length = 32)
    public String sourceSystem;
    @Column(length = 64)
    public String sourceRecordId;
    @Column(length = 64)
    public String sourcePatientId;
    @Column(length = 32)
    public String campusCode;
    @Column(length = 16)
    public String eventType;
    @Column(length = 32)
    public String serviceType;
    public Instant occurredAt;
}
