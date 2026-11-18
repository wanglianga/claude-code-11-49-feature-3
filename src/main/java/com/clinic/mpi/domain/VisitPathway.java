package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 跨院路径段：同一主体的两个确定性就诊事件之间的关系。
 *
 * <p>跨院转诊需有转诊单等关联凭据才能确认；缺凭据的跨院相邻事件标记为
 * UNVERIFIED_TRANSFER（可能重复 / 可能转院，不并入确定路径）。转诊记录晚到
 * （receivedAt 与 occurredAt 相差超过阈值）标记为 LATE_PENDING，不能强行增加复诊次数。</p>
 */
@Entity
@Table(name = "visit_pathway")
public class VisitPathway {

    public enum Type {
        /** 跨院转诊（有转诊凭据） */
        CROSS_CAMPUS_REFERRAL,
        /** 同院独立返院（各自有独立就诊凭据） */
        SAME_CAMPUS_RETURN,
        /** 跨院但缺少转诊凭据 */
        UNVERIFIED_TRANSFER,
        /** 跨院但间隔超过转院窗口，判定为独立跨院就诊 */
        CROSS_CAMPUS_VISIT
    }

    public enum Status {CONFIRMED, LATE_PENDING, UNVERIFIED}

    @Id
    public UUID id = UUID.randomUUID();

    @Column(nullable = false)
    public UUID datasetId;

    @Column(nullable = false)
    public UUID masterId;

    @Column(nullable = false)
    public UUID fromEventId;

    @Column(nullable = false)
    public UUID toEventId;

    @Column(nullable = false, length = 32)
    public String fromCampus;

    @Column(nullable = false, length = 32)
    public String toCampus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    public Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status;

    public UUID referralRecordId;

    @Column(length = 64)
    public String referralKey;

    /** 两个事件之间的间隔（分钟），用于路径时长统计 */
    @Column(nullable = false)
    public long gapMinutes;

    @Column(length = 1000)
    public String evidence;
}
