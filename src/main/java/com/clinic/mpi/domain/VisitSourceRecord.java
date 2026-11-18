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
import java.time.LocalDate;
import java.util.UUID;

/**
 * 跨系统就诊来源记录（挂号 / 签到 / 检验 / 处方 / 转诊 / 影像）。
 *
 * <p>记录保留来源系统、来源流水号、来源时间、业务地点、服务类型和关联凭据
 * （就诊流水 encounterKey、转诊单 referralKey）。记录本身只描述"当时发生了什么"，
 * 不保存任何诊断或结算结果——事件归并不得依据后续结果反推。</p>
 *
 * <p>同一来源流水号重复回传不会新增行：内容一致计为重复回传（reuploadCount），
 * 内容变化则升版本（revision）。</p>
 */
@Entity
@Table(name = "visit_source_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"sourceSystem", "sourceRecordId"}))
public class VisitSourceRecord {

    public enum EventType {REGISTRATION, CHECK_IN, LAB, PRESCRIPTION, REFERRAL, IMAGING}

    /** EXACT = 带具体时间；DATE_ONLY = 只有日期，不能放入时间窗。 */
    public enum TimePrecision {EXACT, DATE_ONLY}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 64)
    public String sourceRecordId;

    @Column(nullable = false, length = 64)
    public String sourcePatientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public EventType eventType;

    /** 服务类型：OUTPATIENT / EMERGENCY / LAB / PHARMACY / IMAGING / REFERRAL */
    @Column(nullable = false, length = 32)
    public String serviceType;

    /** 业务地点（院区 / 中心编码，如院区A、院区B、影像中心） */
    @Column(nullable = false, length = 32)
    public String campusCode;

    public Instant occurredAt;

    public LocalDate occurredDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    public TimePrecision timePrecision;

    /** 关联凭据：挂号流水 / 就诊号，可用于确定性事件归并 */
    @Column(length = 64)
    public String encounterKey;

    /** 关联凭据：转诊单号（转诊记录本身或转诊目标记录携带） */
    @Column(length = 64)
    public String referralKey;

    @Column(length = 32)
    public String referralTargetCampus;

    /** 明细：检验项目名、处方内容等，仅用于复查间隔统计与审计 */
    @Column(length = 128)
    public String serviceDetail;

    public Instant firstReceivedAt;

    /** 最近一次到达本服务的时间；与 occurredAt 的差值用于识别晚到记录 */
    public Instant receivedAt;

    @Column(nullable = false)
    public int revision = 1;

    /** 同记录号、同内容的重复回传次数 */
    @Column(nullable = false)
    public int reuploadCount = 0;

    /** 运营人工核验过转诊单（晚到转诊单经确认后按确定转诊处理） */
    @Column(nullable = false)
    public boolean referralVerified = false;

    @Column(length = 64)
    public String referralVerifiedBy;

    @Column(length = 500)
    public String referralVerifyNote;

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();
}
