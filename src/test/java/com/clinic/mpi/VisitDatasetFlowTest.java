package com.clinic.mpi;

import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.VisitCenterStats;
import com.clinic.mpi.domain.VisitDataset;
import com.clinic.mpi.domain.VisitDatasetRecord;
import com.clinic.mpi.domain.VisitEpisode;
import com.clinic.mpi.domain.VisitEvent;
import com.clinic.mpi.domain.VisitEventMember;
import com.clinic.mpi.domain.VisitLabInterval;
import com.clinic.mpi.domain.VisitPathway;
import com.clinic.mpi.domain.VisitQualityFinding;
import com.clinic.mpi.domain.VisitSourceRecord;
import com.clinic.mpi.service.BadRequestException;
import com.clinic.mpi.service.SnapshotService;
import com.clinic.mpi.service.VisitDatasetService;
import com.clinic.mpi.service.VisitRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨系统就诊事件归并与路径数据集验收测试（真实 PostgreSQL，ccdev_test 库）：
 * 同日独立急诊、跨院转诊、重复检验回传、日期级时间、晚到转诊单、身份未确定、
 * 版本化与快照隔离、待定类别不增删复诊次数。
 */
@SpringBootTest
class VisitDatasetFlowTest {

    @Autowired VisitRecordService recordService;
    @Autowired VisitDatasetService datasetService;
    @Autowired SnapshotService snapshotService;

    private static int seq = 0;

    private String uid(String tag) {
        return "V-" + tag + "-" + (++seq) + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    // ---- 工具 ----

    @Autowired
    com.clinic.mpi.service.IdentityService identityService;

    private String ingest(String tag) {
        String pid = uid(tag);
        identityService.ingest("HIS_T", pid, "测试" + tag, "13" + (100000000 + seq), null, "M",
                LocalDate.of(1990, 1, 1));
        return pid;
    }

    private UUID snapshot() {
        return snapshotService.publish(Snapshot.BusinessType.VISIT,
                "测试就诊快照-" + UUID.randomUUID(), "test").id;
    }

    private void rec(String pid, String recId, String type, String service, String campus,
                     String at, String date, String enc, String ref, String refTarget, String detail) {
        recordService.ingest(new VisitRecordService.IngestCommand(
                "HIS_T", recId, pid, type, service, campus, at, date, enc, ref, refTarget, detail, at));
    }

    private VisitDataset compile(UUID snapshotId) {
        return datasetService.compile(snapshotId, "测试编译", "test");
    }

    private List<VisitDatasetRecord> recordsOf(VisitDataset ds, String pid) {
        return datasetService.recordsOf(ds.id).stream()
                .filter(r -> pid.equals(r.sourcePatientId)).toList();
    }

    private List<VisitEvent> eventsOf(VisitDataset ds, UUID masterId) {
        return datasetService.eventsOf(ds.id, null, masterId);
    }

    // ---- 1. 同日独立急诊与门诊分开成事件 ----
    @Test
    void sameDayIndependentEmergencyIsNotMergedIntoOutpatient() {
        String pid = ingest("急诊");
        UUID snap = snapshot();
        rec(pid, pid + "-R1", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-03T09:00:00Z", null, pid + "-ENC1", null, null, null);
        rec(pid, pid + "-R2", "LAB", "LAB", "采血中心A",
                "2026-11-03T09:40:00Z", null, pid + "-ENC1", null, null, "血常规");
        rec(pid, pid + "-R3", "REGISTRATION", "EMERGENCY", "院区A",
                "2026-11-03T20:10:00Z", null, pid + "-ENC2", null, null, null);

        VisitDataset ds = compile(snap);
        List<VisitDatasetRecord> recs = recordsOf(ds, pid);
        assertThat(recs).hasSize(3);
        // 门诊与急诊分属两个不同事件
        long distinctEvents = recs.stream().map(r -> r.eventId).distinct().count();
        assertThat(distinctEvents).isEqualTo(2);
        List<VisitEvent> events = eventsOf(ds, recs.get(0).masterId);
        assertThat(events).extracting(e -> e.status)
                .containsOnly(VisitEvent.Status.CONFIRMED);
        // 两个事件不并入同一 episode（同院独立返院）
        assertThat(events).extracting(e -> e.episodeKey).doesNotContainNull().hasSize(2);
        assertThat(events.get(0).episodeKey).isNotEqualTo(events.get(1).episodeKey);    }

    // ---- 2. 跨院转诊合并为一个诊疗过程 ----
    @Test
    void crossCampusReferralMergesIntoSingleEpisode() {
        String pid = ingest("转诊");
        UUID snap = snapshot();
        rec(pid, pid + "-R1", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-02T09:00:00Z", null, pid + "-A", null, null, null);
        rec(pid, pid + "-R2", "REFERRAL", "REFERRAL", "院区A",
                "2026-11-02T10:20:00Z", null, pid + "-A", pid + "-REF", "院区B", null);
        rec(pid, pid + "-R3", "REGISTRATION", "OUTPATIENT", "院区B",
                "2026-11-02T14:00:00Z", null, pid + "-B", pid + "-REF", "院区B", null);

        VisitDataset ds = compile(snap);
        List<VisitDatasetRecord> recs = recordsOf(ds, pid);
        UUID master = recs.get(0).masterId;
        List<VisitEvent> events = eventsOf(ds, master);
        assertThat(events).hasSize(2);
        assertThat(events.stream().map(e -> e.episodeKey).distinct().count()).isEqualTo(1);
        List<VisitPathway> pathways = datasetService.pathwaysOf(ds.id, master, null);
        assertThat(pathways).anyMatch(p -> p.type == VisitPathway.Type.CROSS_CAMPUS_REFERRAL
                && p.status == VisitPathway.Status.CONFIRMED);
        // 一个跨院过程只算 1 次复诊（而不是 2 个院区各一次）
        List<VisitEpisode> episodes = datasetService.episodesOf(ds.id, master);
        assertThat(episodes).hasSize(1);
        assertThat(episodes.get(0).campusCount).isEqualTo(2);
    }

    // ---- 3. 重复检验回传被去重，复查间隔不重复计算 ----
    @Test
    void duplicateLabReuploadIsDedupedAndLabIntervalCountedOnce() {
        String pid = ingest("复查");
        UUID snap = snapshot();
        rec(pid, pid + "-R1", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-05T09:00:00Z", null, pid + "-A", null, null, null);
        rec(pid, pid + "-R2", "LAB", "LAB", "采血中心A",
                "2026-11-05T09:30:00Z", null, pid + "-A", null, null, "血常规");
        rec(pid, pid + "-R3", "LAB", "LAB", "采血中心A",
                "2026-11-05T09:32:00Z", null, pid + "-A", null, null, "血常规");
        rec(pid, pid + "-R4", "REGISTRATION", "OUTPATIENT", "院区B",
                "2026-11-12T10:00:00Z", null, pid + "-B", null, null, null);
        rec(pid, pid + "-R5", "LAB", "LAB", "采血中心B",
                "2026-11-12T10:20:00Z", null, pid + "-B", null, null, "血常规");

        VisitDataset ds = compile(snap);
        List<VisitDatasetRecord> recs = recordsOf(ds, pid);
        assertThat(recs).filteredOn(r -> r.recordId.toString().length() > 0).hasSize(5);
        assertThat(recs).filteredOn(
                r -> r.classification == VisitDatasetRecord.Classification.POSSIBLE_DUPLICATE).hasSize(1);
        UUID master = recs.get(0).masterId;
        List<VisitLabInterval> intervals = datasetService.labIntervalsOf(ds.id, master);
        assertThat(intervals).hasSize(1);
        assertThat(intervals.get(0).testName).isEqualTo("血常规");
        assertThat(intervals.get(0).intervalHours).isGreaterThan(150);
        assertThat(intervals.get(0).crossCampus).isTrue();
        assertThat(ds.possibleDuplicateRecords).isGreaterThanOrEqualTo(1);
    }

    // ---- 4. 日期级时间进入待定，不强行增加复诊 ----
    @Test
    void dateOnlyRecordsGoToAmbiguousCategory() {
        String pid = ingest("日期级");
        UUID snap = snapshot();
        recordService.ingest(new VisitRecordService.IngestCommand("HIS_T", pid + "-R1", pid,
                "REGISTRATION", "OUTPATIENT", "院区A", null, "2026-11-04", null, null, null, null, null));
        recordService.ingest(new VisitRecordService.IngestCommand("HIS_T", pid + "-R2", pid,
                "LAB", "LAB", "采血中心A", null, "2026-11-04", null, null, null, "血糖", null));

        VisitDataset ds = compile(snap);
        List<VisitDatasetRecord> recs = recordsOf(ds, pid);
        assertThat(recs).hasSize(2).allMatch(r ->
                r.classification == VisitDatasetRecord.Classification.AMBIGUOUS_TIME_EVENT);
        UUID master = recs.get(0).masterId;
        assertThat(datasetService.episodesOf(ds.id, master)).isEmpty();
        assertThat(datasetService.qualityOf(ds.id, null))
                .anyMatch(f -> f.category == VisitQualityFinding.Category.DATE_ONLY_TIME);
    }

    // ---- 5. 晚到转诊单：先待定，核验后新版本变为确定 ----
    @Test
    void lateReferralGoesToPendingThenConfirmedAfterVerification() {
        String pid = ingest("晚到");
        UUID snap = snapshot();
        recordService.ingest(new VisitRecordService.IngestCommand("HIS_T", pid + "-R1", pid,
                "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-10-20T09:00:00Z", null, pid + "-A", null, null, null, "2026-10-20T09:00:00Z"));
        VisitSourceRecord referral = recordService.ingest(new VisitRecordService.IngestCommand(
                "HIS_T", pid + "-R2", pid, "REFERRAL", "REFERRAL", "院区A",
                "2026-10-20T10:00:00Z", null, pid + "-A", pid + "-REF", "院区B", null,
                "2026-10-27T08:00:00Z"));
        recordService.ingest(new VisitRecordService.IngestCommand("HIS_T", pid + "-R3", pid,
                "REGISTRATION", "OUTPATIENT", "院区B",
                "2026-10-21T14:00:00Z", null, pid + "-B", pid + "-REF", "院区B", null,
                "2026-10-21T14:00:00Z"));

        VisitDataset ds1 = compile(snap);
        UUID master = recordsOf(ds1, pid).get(0).masterId;
        assertThat(datasetService.pathwaysOf(ds1.id, master, null))
                .anyMatch(p -> p.status == VisitPathway.Status.LATE_PENDING);
        assertThat(datasetService.qualityOf(ds1.id, null))
                .anyMatch(f -> f.category == VisitQualityFinding.Category.LATE_REFERRAL);
        // 待定：目标侧就诊不进入确定 episodes（该主体只有 A 侧 1 个确定过程）
        assertThat(datasetService.episodesOf(ds1.id, master)).hasSize(1);

        // 人工核验晚到转诊单 -> 新版本
        recordService.verifyReferral(referral.id, "已核对纸质转诊单", "op_test");
        VisitDataset ds2 = datasetService.compile(snap, "核验后", "test");
        assertThat(ds2.datasetVersion).isGreaterThan(ds1.datasetVersion);
        assertThat(datasetService.pathwaysOf(ds2.id, master, null))
                .anyMatch(p -> p.type == VisitPathway.Type.CROSS_CAMPUS_REFERRAL
                        && p.status == VisitPathway.Status.CONFIRMED);
        // 核验后 A→B 属于同一诊疗过程：仍 1 个 episode
        assertThat(datasetService.episodesOf(ds2.id, master)).hasSize(1);
        // 旧版本内容不被回写
        assertThat(datasetService.pathwaysOf(ds1.id, master, null))
                .noneMatch(p -> p.status == VisitPathway.Status.CONFIRMED
                        && p.type == VisitPathway.Type.CROSS_CAMPUS_REFERRAL);
    }

    // ---- 6. 身份未确定：无法归并并单独报告 ----
    @Test
    void unresolvedIdentityIsReportedAsUnmergeable() {
        UUID snap = snapshot();
        String unknown = "UNKNOWN-" + UUID.randomUUID();
        recordService.ingest(new VisitRecordService.IngestCommand("REG_X", unknown + "-R1", unknown,
                "REGISTRATION", "OUTPATIENT", "院区C", "2026-11-07T15:00:00Z", null, null,
                null, null, null, null));
        VisitDataset ds = compile(snap);
        List<VisitDatasetRecord> recs = recordsOf(ds, unknown);
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).classification)
                .isEqualTo(VisitDatasetRecord.Classification.UNRESOLVED_IDENTITY);
        assertThat(ds.identityStatus).isEqualTo(VisitDataset.PhaseStatus.PARTIAL);
        assertThat(datasetService.qualityOf(ds.id, null))
                .anyMatch(f -> f.category == VisitQualityFinding.Category.UNRESOLVED_IDENTITY);
    }

    // ---- 7. 记录粒度不影响复诊率 ----
    @Test
    void recordGranularityDoesNotInflateRecurrenceRate() {
        String pid = ingest("粒度");
        UUID snap = snapshot();
        // 使用独立院区编码，避免与播种数据的院区统计混在一起
        String campus = "院区T-" + uid("C");
        // 一次就诊：挂号 + 签到 + 检验 + 处方（4 条记录，同凭据）
        rec(pid, pid + "-R1", "REGISTRATION", "OUTPATIENT", campus,
                "2026-11-01T09:00:00Z", null, pid + "-E1", null, null, null);
        rec(pid, pid + "-R2", "CHECK_IN", "OUTPATIENT", campus,
                "2026-11-01T09:10:00Z", null, pid + "-E1", null, null, null);
        rec(pid, pid + "-R3", "LAB", "LAB", campus + "-采血",
                "2026-11-01T09:30:00Z", null, pid + "-E1", null, null, "血常规");
        rec(pid, pid + "-R4", "PRESCRIPTION", "PHARMACY", campus,
                "2026-11-01T09:50:00Z", null, pid + "-E1", null, null, "维生素C");

        VisitDataset ds = compile(snap);
        UUID master = recordsOf(ds, pid).get(0).masterId;
        assertThat(datasetService.eventsOf(ds.id, null, master)).hasSize(1);
        assertThat(datasetService.episodesOf(ds.id, master)).hasSize(1);
        List<VisitCenterStats> stats = datasetService.centerStatsOf(ds.id);
        VisitCenterStats campusStats = stats.stream()
                .filter(s -> campus.equals(s.campusCode)).findFirst().orElseThrow();
        assertThat(campusStats.recordCount).isEqualTo(3);
        assertThat(campusStats.episodes).isEqualTo(1);
        assertThat(campusStats.patientsWithRecurrence).isZero();
        assertThat(campusStats.recurrenceRate).isZero();
    }

    // ---- 8. 同输入幂等；补传记录生成新版本 ----
    @Test
    void recompileIsIdempotentAndBackfillCreatesNewVersion() {
        String pid = ingest("版本");
        UUID snap = snapshot();
        rec(pid, pid + "-R1", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-09T09:00:00Z", null, pid + "-A", null, null, null);
        VisitDataset v1 = compile(snap);
        VisitDataset v1b = compile(snap);
        assertThat(v1b.id).isEqualTo(v1.id); // 输入未变：不产生新版本

        rec(pid, pid + "-R2", "REGISTRATION", "EMERGENCY", "院区A",
                "2026-11-09T21:00:00Z", null, pid + "-B", null, null, null);
        VisitDataset v2 = compile(snap);
        assertThat(v2.datasetVersion).isGreaterThan(v1.datasetVersion);
        assertThat(v2.previousDatasetId).isEqualTo(v1.id);
        assertThat(v2.totalRecords).isEqualTo(v1.totalRecords + 1);
    }

    // ---- 9. 非 VISIT 快照被拒绝 ----
    @Test
    void nonVisitSnapshotIsRejected() {
        Snapshot appt = snapshotService.publish(Snapshot.BusinessType.APPOINTMENT,
                "预约快照-" + UUID.randomUUID(), "test");
        assertThatThrownBy(() -> compile(appt.id))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("VISIT");
    }

    // ---- 10. 无凭据只能形成可能事件 ----
    @Test
    void recordsWithoutCredentialsStayProbable() {
        String pid = ingest("无凭据");
        UUID snap = snapshot();
        rec(pid, pid + "-R1", "IMAGING", "IMAGING", "影像中心",
                "2026-11-06T10:00:00Z", null, null, null, null, "胸部CT");
        VisitDataset ds = compile(snap);
        List<VisitDatasetRecord> recs = recordsOf(ds, pid);
        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).classification)
                .isEqualTo(VisitDatasetRecord.Classification.PROBABLE_EVENT);
        assertThat(datasetService.episodesOf(ds.id, recs.get(0).masterId)).isEmpty();
    }
}
