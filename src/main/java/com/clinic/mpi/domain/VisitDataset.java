package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 就诊事件数据集（不可变版本）。
 *
 * <p>每个数据集固定引用一份已发布身份快照，并且只在"快照修订"或"补传记录"后
 * 生成新版本（datasetVersion 单调递增，previousDatasetId 串成版本链）。
 * 版本内容永不回写：旧版本的路径、事件与统计保持原样，便于审计复现。</p>
 *
 * <p>事件编译、身份快照引用、来源去重、路径统计、质量审查各自独立持有状态，
 * 便于分阶段排查，也避免一个阶段失败时把其他阶段标记为完成。</p>
 */
@Entity
@Table(name = "visit_dataset",
        uniqueConstraints = @UniqueConstraint(columnNames = {"datasetVersion"}))
public class VisitDataset {

    public enum PhaseStatus {PENDING, RUNNING, COMPLETED, PARTIAL, FAILED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** 数据集版本号，单调递增 */
    @Column(nullable = false)
    public long datasetVersion;

    /** 引用的已发布身份快照 */
    @Column(nullable = false)
    public UUID snapshotId;

    /** 快照固定的图谱版本 */
    @Column(nullable = false)
    public long graphVersion;

    @Column(length = 200)
    public String label;

    @Column(nullable = false, length = 64)
    public String createdBy;

    public UUID previousDatasetId;

    /** 输入指纹：快照 + 参数 + 记录版本；相同指纹重复编译直接返回同一版本 */
    @Column(length = 80)
    public String inputFingerprint;

    /** 本次编译采用的时间窗（分钟）与晚到阈值（天），随版本固化以便复现 */
    @Column(nullable = false)
    public int timeWindowMinutes;

    @Column(nullable = false)
    public int lateReferralDays;

    // ---- 五个阶段各自持有状态 ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public PhaseStatus identityStatus = PhaseStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public PhaseStatus dedupStatus = PhaseStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public PhaseStatus compileStatus = PhaseStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public PhaseStatus pathStatus = PhaseStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public PhaseStatus qualityStatus = PhaseStatus.PENDING;

    // ---- 记录级计数 ----
    @Column(nullable = false)
    public int totalRecords;
    @Column(nullable = false)
    public int resolvedRecords;
    /** 身份尚未确定，无法归并 */
    @Column(nullable = false)
    public int unresolvedRecords;
    /** 同源同业务指纹或同流水重复回传，已排除计数 */
    @Column(nullable = false)
    public int possibleDuplicateRecords;
    /** 只有日期级时间或同日同院多事件无法判断归属 */
    @Column(nullable = false)
    public int ambiguousRecords;
    /** 仅靠时间窗聚合、缺少凭据的记录 */
    @Column(nullable = false)
    public int probableRecords;
    /** 归并受限（转诊晚到 / 跨院缺凭据）而进入待定的记录 */
    @Column(nullable = false)
    public int pendingReviewRecords;

    /** 待定事件数：事件本身确定，但归属或路径待核验，不进入确定复诊统计 */
    @Column(nullable = false)
    public int pendingReviewEvents;
    /** 无法归并范围 = 身份未定 + 日期级歧义（不含可能重复） */
    @Column(nullable = false)
    public int unmergeableRecords;

    // ---- 事件 / 路径级计数 ----
    @Column(nullable = false)
    public int confirmedEvents;
    @Column(nullable = false)
    public int probableEvents;
    @Column(nullable = false)
    public int ambiguousTimeEvents;
    @Column(nullable = false)
    public int confirmedEpisodes;
    @Column(nullable = false)
    public int crossCampusEpisodes;
    @Column(nullable = false)
    public int confirmedPathways;
    @Column(nullable = false)
    public int latePendingPathways;
    @Column(nullable = false)
    public int unverifiedTransferPathways;
    @Column(nullable = false)
    public int labIntervalCount;
    @Column(nullable = false)
    public int qualityFindingCount;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    public Instant completedAt;
}
