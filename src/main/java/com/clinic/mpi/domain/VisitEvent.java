package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 就诊事件：同一快照主体、同一业务地点、由凭据或服务链确定的一次就诊。
 *
 * <p>规则优先级：就诊流水凭据（encounterKey）&gt; 转诊关系 &gt; 时间窗 + 服务链。
 * 仅靠时间窗产生的 PROBABLE 事件与日期级时间的 AMBIGUOUS_TIME 事件都不计入
 * "确定复诊"统计。</p>
 */
@Entity
@Table(name = "visit_event")
public class VisitEvent {

    public enum Status {
        /** 凭据 / 转诊 / 服务链确定 */
        CONFIRMED,
        /** 只有时间窗聚合，缺少凭据 */
        PROBABLE,
        /** 只有日期级时间等，无法定位 */
        AMBIGUOUS_TIME
    }

    @Id
    public UUID id = UUID.randomUUID();

    @Column(nullable = false)
    public UUID datasetId;

    /** 数据集内可读编号，如 E0001 */
    @Column(nullable = false, length = 16)
    public String eventKey;

    @Column(nullable = false)
    public UUID masterId;

    @Column(nullable = false, length = 32)
    public String campusCode;

    @Column(nullable = false, length = 32)
    public String serviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    public VisitSourceRecord.TimePrecision timePrecision = VisitSourceRecord.TimePrecision.EXACT;

    public Instant startAt;

    public Instant endAt;

    /** 日期级事件的业务日期 */
    public LocalDate occurredDate;

    /** 归并依据（JSON 数组），用于审计 */
    @Column(length = 1000)
    public String evidence;

    @Column(nullable = false)
    public int memberCount;

    /** 所属诊疗过程；未确认跨院转诊的事件各自成段 */
    @Column(length = 16)
    public String episodeKey;

    /**
     * 是否处于"待定"范围（跨院相邻事件缺少转诊凭据，或转诊单晚到）。
     * 待定事件不进入各中心确定复诊率，单独计入待定桶，避免强行增删复诊次数。
     */
    @Column(nullable = false)
    public boolean pendingReview = false;

    @Column(length = 200)
    public String pendingReason;
}
