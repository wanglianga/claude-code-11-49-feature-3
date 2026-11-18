package com.clinic.mpi.web;

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
import com.clinic.mpi.service.VisitDatasetService;
import com.clinic.mpi.service.VisitRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "就诊事件与跨院路径", description = "跨系统就诊记录接入、事件归并、跨院路径与运营统计数据集")
@RestController
@RequestMapping("/api")
public class VisitController {

    private final VisitRecordService recordService;
    private final VisitDatasetService datasetService;

    public VisitController(VisitRecordService recordService, VisitDatasetService datasetService) {
        this.recordService = recordService;
        this.datasetService = datasetService;
    }

    // ---------------- 来源记录 ----------------

    @Operation(summary = "接入来源就诊记录",
            description = "挂号/签到/检验/处方/转诊/影像记录。同一来源流水号幂等：内容相同计重复回传，"
                    + "内容变化升版本。只有日期级时间时可不传 occurredAt。")
    @PostMapping("/visit-records")
    public VisitSourceRecord ingest(@Valid @RequestBody Dtos.VisitRecordRequest req) {
        return recordService.ingest(new VisitRecordService.IngestCommand(
                req.sourceSystem(), req.sourceRecordId(), req.sourcePatientId(), req.eventType(),
                req.serviceType(), req.campusCode(), req.occurredAt(), req.occurredDate(),
                req.encounterKey(), req.referralKey(), req.referralTargetCampus(),
                req.serviceDetail(), req.receivedAt()));
    }

    @Operation(summary = "来源记录列表", description = "可按 sourceSystem + sourcePatientId 过滤")
    @GetMapping("/visit-records")
    public List<VisitSourceRecord> records(
            @RequestParam(required = false) String sourceSystem,
            @RequestParam(required = false) String sourcePatientId) {
        return recordService.list(sourceSystem, sourcePatientId);
    }

    @Operation(summary = "来源记录详情")
    @GetMapping("/visit-records/{id}")
    public VisitSourceRecord record(@PathVariable UUID id) {
        return recordService.get(id);
    }

    @Operation(summary = "核验晚到转诊单",
            description = "人工核对晚到转诊单后标记核验；下一次数据集编译按确定转诊处理")
    @PostMapping("/visit-records/{id}/verify-referral")
    public VisitSourceRecord verifyReferral(@PathVariable UUID id,
                                            @RequestBody(required = false) Dtos.AckImpactRequest req,
                                            @RequestHeader(name = "X-Operator-Id", defaultValue = "system")
                                            String operator) {
        return recordService.verifyReferral(id, req == null ? null : req.note(), operator);
    }

    // ---------------- 数据集 ----------------

    @Operation(summary = "编译就诊事件数据集",
            description = "引用已发布 VISIT 快照（为空时取最新）编译新版本。快照修订或补传记录后再次编译"
                    + "产生新版本；输入未变化则幂等返回现有版本。")
    @PostMapping("/visit-datasets")
    public VisitDataset compile(@RequestBody(required = false) Dtos.CompileVisitDatasetRequest req,
                               @RequestHeader(name = "X-Operator-Id", defaultValue = "system")
                               String operator) {
        return datasetService.compile(req == null ? null : req.snapshotId(),
                req == null ? null : req.label(), operator);
    }

    @Operation(summary = "数据集版本列表", description = "按版本号倒序，含五阶段状态与记录级计数")
    @GetMapping("/visit-datasets")
    public List<VisitDataset> datasets() {
        return datasetService.datasets();
    }

    @Operation(summary = "最新数据集版本")
    @GetMapping("/visit-datasets/latest")
    public VisitDataset latest() {
        return datasetService.latest();
    }

    @Operation(summary = "数据集详情")
    @GetMapping("/visit-datasets/{id}")
    public VisitDataset dataset(@PathVariable UUID id) {
        return datasetService.get(id);
    }

    @Operation(summary = "数据集记录分类清单",
            description = "每条来源记录在本版本中的身份引用、去重结论与归并分类")
    @GetMapping("/visit-datasets/{id}/records")
    public List<VisitDatasetRecord> recordsOf(@PathVariable UUID id) {
        return datasetService.recordsOf(id);
    }

    @Operation(summary = "数据集事件列表", description = "status=CONFIRMED / PROBABLE / AMBIGUOUS_TIME / PENDING_REVIEW")
    @GetMapping("/visit-datasets/{id}/events")
    public List<VisitEvent> events(@PathVariable UUID id,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) UUID masterId) {
        return datasetService.eventsOf(id, status, masterId);
    }

    @Operation(summary = "事件成员明细", description = "记录归入该事件的规则与成员状态，便于审计")
    @GetMapping("/visit-events/{eventId}/members")
    public List<VisitEventMember> eventMembers(@PathVariable UUID eventId) {
        return datasetService.membersOf(eventId);
    }

    @Operation(summary = "跨院路径列表", description = "可按主体或状态（CONFIRMED / LATE_PENDING / UNVERIFIED）过滤")
    @GetMapping("/visit-datasets/{id}/pathways")
    public List<VisitPathway> pathways(@PathVariable UUID id,
                                       @RequestParam(required = false) UUID masterId,
                                       @RequestParam(required = false) String status) {
        return datasetService.pathwaysOf(id, masterId, status);
    }

    @Operation(summary = "诊疗过程列表", description = "复诊统计以 process(episode) 为单位，跨院转诊合并为一个过程")
    @GetMapping("/visit-datasets/{id}/episodes")
    public List<VisitEpisode> episodes(@PathVariable UUID id,
                                       @RequestParam(required = false) UUID masterId) {
        return datasetService.episodesOf(id, masterId);
    }

    @Operation(summary = "检验复查间隔")
    @GetMapping("/visit-datasets/{id}/lab-intervals")
    public List<VisitLabInterval> labIntervals(@PathVariable UUID id,
                                               @RequestParam(required = false) UUID masterId) {
        return datasetService.labIntervalsOf(id, masterId);
    }

    @Operation(summary = "各中心复诊统计", description = "复诊率按 episode 计，不受来源记录粒度影响")
    @GetMapping("/visit-datasets/{id}/center-stats")
    public List<VisitCenterStats> centerStats(@PathVariable UUID id) {
        return datasetService.centerStatsOf(id);
    }

    @Operation(summary = "质量审查条目", description = "无法归并 / 可能重复 / 待定分类；status=PENDING 查看待处理")
    @GetMapping("/visit-datasets/{id}/quality")
    public List<VisitQualityFinding> quality(@PathVariable UUID id,
                                             @RequestParam(required = false) String status) {
        return datasetService.qualityOf(id, status);
    }

    @Operation(summary = "处理质量审查条目", description = "ack 关闭或 dismiss 忽略；不改变数据集版本内容")
    @PostMapping("/visit-quality/{findingId}/ack")
    public VisitQualityFinding ackFinding(@PathVariable UUID findingId,
                                          @RequestBody(required = false) Dtos.AckQualityRequest req,
                                          @RequestHeader(name = "X-Operator-Id", defaultValue = "system")
                                          String operator) {
        return datasetService.ackFinding(findingId,
                req != null && Boolean.TRUE.equals(req.dismiss()),
                req == null ? null : req.note(), operator);
    }

    // ---------------- 运营报告 ----------------

    @Operation(summary = "运营报告",
            description = "汇总确定路径、可能重复、无法归并三类口径和各中心复诊率，供临床运营直接出报表")
    @GetMapping("/visit-datasets/{id}/report")
    public Map<String, Object> report(@PathVariable UUID id) {
        VisitDataset ds = datasetService.get(id);
        List<VisitCenterStats> stats = datasetService.centerStatsOf(id);
        List<VisitQualityFinding> findings = datasetService.qualityOf(id, null);
        long pending = findings.stream()
                .filter(f -> f.status == VisitQualityFinding.Status.PENDING).count();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (VisitQualityFinding f : findings) {
            byCategory.merge(f.category.name(), 1, Integer::sum);
        }

        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("description", "凭据 / 转诊 / 服务链确定的就诊事件与跨院路径");
        confirmed.put("events", ds.confirmedEvents);
        confirmed.put("episodes", ds.confirmedEpisodes);
        confirmed.put("crossCampusEpisodes", ds.crossCampusEpisodes);
        confirmed.put("pathways", ds.confirmedPathways);

        Map<String, Object> possibleDuplicate = new LinkedHashMap<>();
        possibleDuplicate.put("description", "同源同业务指纹或同流水重复回传，已从计数中剔除并报告");
        possibleDuplicate.put("records", ds.possibleDuplicateRecords);

        Map<String, Object> unmergeable = new LinkedHashMap<>();
        unmergeable.put("description", "身份尚未确定或时间只有日期，无法归并到确定事件");
        unmergeable.put("records", ds.unmergeableRecords);
        unmergeable.put("unresolvedIdentity", ds.unresolvedRecords);
        unmergeable.put("ambiguousTime", ds.ambiguousRecords);

        Map<String, Object> pendingReview = new LinkedHashMap<>();
        pendingReview.put("description", "转诊单晚到或跨院缺少转诊凭据，只能进入待定类别，不能强行增删复诊次数");
        pendingReview.put("latePendingPathways", ds.latePendingPathways);
        pendingReview.put("unverifiedTransferPathways", ds.unverifiedTransferPathways);
        pendingReview.put("records", ds.pendingReviewRecords);
        pendingReview.put("probableRecords", ds.probableRecords);
        pendingReview.put("qualityFindingsPending", pending);

        List<Map<String, Object>> centers = new ArrayList<>();
        for (VisitCenterStats s : stats) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("campusCode", s.campusCode);
            c.put("recordCount", s.recordCount);
            c.put("episodes", s.episodes);
            c.put("patients", s.patients);
            c.put("patientsWithRecurrence", s.patientsWithRecurrence);
            c.put("recurrenceCount", s.recurrenceCount);
            c.put("recurrenceRate", s.recurrenceRate);
            c.put("crossCampusEpisodeCount", s.crossCampusEpisodeCount);
            c.put("duplicateRecordCount", s.duplicateRecordCount);
            c.put("ambiguousRecordCount", s.ambiguousRecordCount);
            c.put("unmergeableRecordCount", s.unmergeableRecordCount);
            c.put("pendingReviewEventCount", s.pendingReviewEventCount);
            c.put("labIntervalCount", s.labIntervalCount);
            centers.add(c);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasetVersion", ds.datasetVersion);
        body.put("datasetId", ds.id);
        body.put("snapshotId", ds.snapshotId);
        body.put("graphVersion", ds.graphVersion);
        body.put("phases", phases(ds));
        body.put("totalRecords", ds.totalRecords);
        body.put("confirmed", confirmed);
        body.put("possibleDuplicate", possibleDuplicate);
        body.put("unmergeable", unmergeable);
        body.put("pendingReview", pendingReview);
        body.put("qualityByCategory", byCategory);
        body.put("centers", centers);
        body.put("labIntervalCount", ds.labIntervalCount);
        return body;
    }

    private Map<String, Object> phases(VisitDataset ds) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("identitySnapshot", ds.identityStatus);
        p.put("sourceDedup", ds.dedupStatus);
        p.put("eventCompile", ds.compileStatus);
        p.put("pathStatistics", ds.pathStatus);
        p.put("qualityReview", ds.qualityStatus);
        return p;
    }
}
