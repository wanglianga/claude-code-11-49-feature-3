package com.clinic.mpi.service;

import com.clinic.mpi.domain.VisitSourceRecord;
import com.clinic.mpi.repo.VisitSourceRecordRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 跨系统就诊来源记录接入。
 *
 * <p>同一来源系统内以 sourceRecordId 幂等：内容相同视为重复回传（reuploadCount+1），
 * 内容变化则升 revision。两种情况都会刷新 receivedAt——晚到转诊单正是靠
 * receivedAt 与 occurredAt 的差值识别。记录只保存"来源当时发生了什么"，
 * 不接收诊断、结算等后续结果，避免下游结果反向影响事件归并。</p>
 */
@Service
public class VisitRecordService {

    private final VisitSourceRecordRepo recordRepo;

    public VisitRecordService(VisitSourceRecordRepo recordRepo) {
        this.recordRepo = recordRepo;
    }

    public record IngestCommand(String sourceSystem, String sourceRecordId, String sourcePatientId,
                                String eventType, String serviceType, String campusCode,
                                String occurredAt, String occurredDate, String encounterKey,
                                String referralKey, String referralTargetCampus,
                                String serviceDetail, String receivedAt) {
    }

    @Transactional
    public VisitSourceRecord ingest(IngestCommand cmd) {
        Instant occurredAt = parseInstant(cmd.occurredAt(), "occurredAt");
        LocalDate occurredDate = parseDate(cmd.occurredDate());
        VisitSourceRecord.EventType eventType = parseEnum(VisitSourceRecord.EventType.class,
                cmd.eventType(), "eventType");
        if (occurredAt == null && occurredDate == null) {
            throw new BadRequestException("occurredAt 与 occurredDate 至少提供一个（只有日期级时间时传 occurredDate）");
        }
        VisitSourceRecord.TimePrecision precision =
                occurredAt != null ? VisitSourceRecord.TimePrecision.EXACT
                        : VisitSourceRecord.TimePrecision.DATE_ONLY;
        if (occurredAt != null) {
            occurredDate = occurredAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        Instant receivedAt = parseInstant(cmd.receivedAt(), "receivedAt");
        Instant now = Instant.now();

        VisitSourceRecord record = recordRepo
                .findBySourceSystemAndSourceRecordId(cmd.sourceSystem(), cmd.sourceRecordId())
                .orElse(null);
        if (record == null) {
            record = new VisitSourceRecord();
            record.sourceSystem = cmd.sourceSystem();
            record.sourceRecordId = cmd.sourceRecordId();
            record.firstReceivedAt = now;
            record.receivedAt = receivedAt != null ? receivedAt : now;
        } else {
            boolean same = sameContent(record, cmd, occurredAt, occurredDate, precision, eventType);
            if (same) {
                record.reuploadCount++;
                record.receivedAt = now;
                record.updatedAt = now;
                return recordRepo.save(record);
            }
            record.revision++;
            record.receivedAt = now;
            // 内容变了，此前对转诊凭据的人工核验失效，需按新内容重新核验
            record.referralVerified = false;
            record.referralVerifiedBy = null;
            record.referralVerifyNote = null;
        }

        record.sourcePatientId = cmd.sourcePatientId();
        record.eventType = eventType;
        record.serviceType = cmd.serviceType();
        record.campusCode = cmd.campusCode();
        record.occurredAt = occurredAt;
        record.occurredDate = occurredDate;
        record.timePrecision = precision;
        record.encounterKey = blankToNull(cmd.encounterKey());
        record.referralKey = blankToNull(cmd.referralKey());
        record.referralTargetCampus = blankToNull(cmd.referralTargetCampus());
        record.serviceDetail = blankToNull(cmd.serviceDetail());
        record.updatedAt = now;
        return recordRepo.save(record);
    }

    /** 运营人工核验晚到的转诊单：核验后下一次编译按确定转诊处理。 */
    @Transactional
    public VisitSourceRecord verifyReferral(UUID recordId, String note, String operatorId) {
        VisitSourceRecord record = recordRepo.findById(recordId)
                .orElseThrow(() -> new NotFoundException("来源记录不存在: " + recordId));
        if (record.referralKey == null || record.referralTargetCampus == null) {
            throw new BadRequestException("该记录不是转诊记录，无法核验转诊凭据");
        }
        record.referralVerified = true;
        record.referralVerifiedBy = operatorId;
        record.referralVerifyNote = note;
        record.updatedAt = Instant.now();
        return recordRepo.save(record);
    }

    @Transactional(readOnly = true)
    public List<VisitSourceRecord> list(String sourceSystem, String sourcePatientId) {
        if (sourceSystem != null && !sourceSystem.isBlank()
                && sourcePatientId != null && !sourcePatientId.isBlank()) {
            return recordRepo.findBySource(sourceSystem, sourcePatientId);
        }
        return recordRepo.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public VisitSourceRecord get(UUID id) {
        return recordRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("来源记录不存在: " + id));
    }

    private boolean sameContent(VisitSourceRecord r, IngestCommand cmd, Instant occurredAt,
                                LocalDate occurredDate, VisitSourceRecord.TimePrecision precision,
                                VisitSourceRecord.EventType eventType) {
        return r.eventType == eventType
                && eq(r.serviceType, cmd.serviceType())
                && eq(r.campusCode, cmd.campusCode())
                && eq(r.occurredAt, occurredAt)
                && eq(r.occurredDate, occurredDate)
                && r.timePrecision == precision
                && eq(r.encounterKey, blankToNull(cmd.encounterKey()))
                && eq(r.referralKey, blankToNull(cmd.referralKey()))
                && eq(r.referralTargetCampus, blankToNull(cmd.referralTargetCampus()))
                && eq(r.serviceDetail, blankToNull(cmd.serviceDetail()));
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Instant parseInstant(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            throw new BadRequestException(field + " 需为 ISO-8601 时间，如 2026-11-01T09:00:00Z");
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new BadRequestException("occurredDate 需为 yyyy-MM-dd");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("不支持的 " + field + ": " + value + "，可选值 "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }
}
