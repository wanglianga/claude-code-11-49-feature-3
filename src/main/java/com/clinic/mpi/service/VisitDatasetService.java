package com.clinic.mpi.service;

import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.domain.SnapshotMapping;
import com.clinic.mpi.domain.VisitCenterStats;
import com.clinic.mpi.domain.VisitDataset;
import com.clinic.mpi.domain.VisitDatasetRecord;
import com.clinic.mpi.domain.VisitDatasetState;
import com.clinic.mpi.domain.VisitEpisode;
import com.clinic.mpi.domain.VisitEvent;
import com.clinic.mpi.domain.VisitEventMember;
import com.clinic.mpi.domain.VisitLabInterval;
import com.clinic.mpi.domain.VisitPathway;
import com.clinic.mpi.domain.VisitQualityFinding;
import com.clinic.mpi.domain.VisitSourceRecord;
import com.clinic.mpi.repo.SnapshotMappingRepo;
import com.clinic.mpi.repo.SnapshotRepo;
import com.clinic.mpi.repo.VisitCenterStatsRepo;
import com.clinic.mpi.repo.VisitDatasetRecordRepo;
import com.clinic.mpi.repo.VisitDatasetRepo;
import com.clinic.mpi.repo.VisitDatasetStateRepo;
import com.clinic.mpi.repo.VisitEpisodeRepo;
import com.clinic.mpi.repo.VisitEventMemberRepo;
import com.clinic.mpi.repo.VisitEventRepo;
import com.clinic.mpi.repo.VisitLabIntervalRepo;
import com.clinic.mpi.repo.VisitPathwayRepo;
import com.clinic.mpi.repo.VisitQualityFindingRepo;
import com.clinic.mpi.repo.VisitSourceRecordRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 跨系统就诊事件归并与跨院路径数据集编译。
 *
 * <p><b>归并规则优先级</b>：就诊流水凭据（encounterKey）&gt; 转诊关系（referralKey）
 * &gt; 服务类型 + 时间窗。凭据明确时不依赖时间窗；缺少凭据时只生成"可能事件"，
 * 不计入确定复诊。</p>
 *
 * <p><b>硬约束</b>：
 * <ul>
 *   <li>不使用诊断 / 结算等后续结果反推此前事件是否合并——来源记录本身就不含这些字段；</li>
 *   <li>时间戳只有日期 → 待定（AMBIGUOUS_TIME），不放入时间窗；</li>
 *   <li>转诊单晚到 → 待定（LATE_PENDING），既不并入也不拆开，需人工核验；</li>
 *   <li>同一来源流水重复 / 同业务指纹重复回传 → 可能重复，剔除计数并报告；</li>
 *   <li>身份尚未确定 → 无法归并，单独报告。</li>
 * </ul></p>
 *
 * <p>五个阶段（身份快照引用、来源去重、事件编译、路径统计、质量审查）各自持有状态；
 * 快照修订或补传记录只会生成新的数据集版本，旧版本永不回写。</p>
 */
@Service
public class VisitDatasetService {

    /** 默认时间窗：同一院区、同一服务轨道内的相邻无凭据记录合并为一次就诊的候选 */
    public static final int DEFAULT_TIME_WINDOW_MINUTES = 120;
    /** 转诊单晚到阈值（天） */
    public static final int DEFAULT_LATE_REFERRAL_DAYS = 1;
    /** 跨院相邻确定事件间隔在此范围内又无转诊凭据时，视为"转院/重复"待定 */
    private static final int TRANSFER_WINDOW_HOURS = 24;
    /** 同源同业务指纹在此窗口内重复出现视为可能重复回传 */
    private static final int DUPLICATE_WINDOW_MINUTES = 10;

    private final VisitSourceRecordRepo recordRepo;
    private final VisitDatasetRepo datasetRepo;
    private final VisitDatasetStateRepo stateRepo;
    private final VisitDatasetRecordRepo datasetRecordRepo;
    private final VisitEventRepo eventRepo;
    private final VisitEventMemberRepo memberRepo;
    private final VisitPathwayRepo pathwayRepo;
    private final VisitEpisodeRepo episodeRepo;
    private final VisitLabIntervalRepo labIntervalRepo;
    private final VisitCenterStatsRepo centerStatsRepo;
    private final VisitQualityFindingRepo findingRepo;
    private final SnapshotRepo snapshotRepo;
    private final SnapshotMappingRepo mappingRepo;

    public VisitDatasetService(VisitSourceRecordRepo recordRepo, VisitDatasetRepo datasetRepo,
                               VisitDatasetStateRepo stateRepo, VisitDatasetRecordRepo datasetRecordRepo,
                               VisitEventRepo eventRepo, VisitEventMemberRepo memberRepo,
                               VisitPathwayRepo pathwayRepo, VisitEpisodeRepo episodeRepo,
                               VisitLabIntervalRepo labIntervalRepo, VisitCenterStatsRepo centerStatsRepo,
                               VisitQualityFindingRepo findingRepo, SnapshotRepo snapshotRepo,
                               SnapshotMappingRepo mappingRepo) {
        this.recordRepo = recordRepo;
        this.datasetRepo = datasetRepo;
        this.stateRepo = stateRepo;
        this.datasetRecordRepo = datasetRecordRepo;
        this.eventRepo = eventRepo;
        this.memberRepo = memberRepo;
        this.pathwayRepo = pathwayRepo;
        this.episodeRepo = episodeRepo;
        this.labIntervalRepo = labIntervalRepo;
        this.centerStatsRepo = centerStatsRepo;
        this.findingRepo = findingRepo;
        this.snapshotRepo = snapshotRepo;
        this.mappingRepo = mappingRepo;
    }

    // ------------------------------------------------------------------
    // 编译入口
    // ------------------------------------------------------------------

    @Transactional
    public VisitDataset compile(UUID snapshotId, String label, String operatorId) {
        Snapshot snapshot = resolveVisitSnapshot(snapshotId);
        int window = DEFAULT_TIME_WINDOW_MINUTES;
        int lateDays = DEFAULT_LATE_REFERRAL_DAYS;

        // 先取得数据集版本锁，再查重与分配版本：并发提交相同输入时只有一个写入新版本，
        // 后到者取锁后能看到前者的指纹并幂等返回，不会产生重复版本号
        VisitDatasetState state = stateRepo.lockSingleton().orElseGet(() -> stateRepo.save(new VisitDatasetState()));
        List<VisitSourceRecord> records = recordRepo.findAll();
        List<SnapshotMapping> mappings = mappingRepo.findBySnapshotId(snapshot.id);
        String fingerprint = fingerprint(snapshot.id, window, lateDays, records);

        VisitDataset existing = datasetRepo.findByInputFingerprint(fingerprint).orElse(null);
        if (existing != null) {
            return existing; // 输入未变化：不产生新版本（幂等）
        }

        long version = state.lastVersion + 1;
        state.lastVersion = version;
        stateRepo.save(state);

        VisitDataset ds = new VisitDataset();
        ds.datasetVersion = version;
        ds.snapshotId = snapshot.id;
        ds.graphVersion = snapshot.graphVersion;
        ds.label = label;
        ds.createdBy = operatorId;
        ds.timeWindowMinutes = window;
        ds.lateReferralDays = lateDays;
        ds.inputFingerprint = fingerprint;
        ds.previousDatasetId = datasetRepo.findFirstByOrderByDatasetVersionDesc()
                .map(d -> d.id).orElse(null);
        ds.identityStatus = VisitDataset.PhaseStatus.RUNNING;
        datasetRepo.save(ds);

        new Compilation(ds, mappings, records).run();
        return datasetRepo.save(ds);
    }

    private Snapshot resolveVisitSnapshot(UUID snapshotId) {
        if (snapshotId != null) {
            Snapshot s = snapshotRepo.findById(snapshotId)
                    .orElseThrow(() -> new NotFoundException("快照不存在: " + snapshotId));
            if (s.businessType != Snapshot.BusinessType.VISIT) {
                throw new BadRequestException("就诊数据集必须引用 VISIT 类型快照，当前为 " + s.businessType);
            }
            if (s.status != Snapshot.Status.PUBLISHED) {
                throw new ConflictException("快照 " + snapshotId + " 状态为 " + s.status
                        + "，只有已发布（PUBLISHED）快照可以作为数据集基线");
            }
            return s;
        }
        return snapshotRepo.findByBusinessTypeAndStatus(Snapshot.BusinessType.VISIT, Snapshot.Status.PUBLISHED)
                .stream()
                .max(Comparator.comparing(s -> s.createdAt))
                .orElseThrow(() -> new BadRequestException(
                        "尚无已发布的 VISIT 快照，请先 POST /api/snapshots 发布 VISIT 快照"));
    }

    private String fingerprint(UUID snapshotId, int window, int lateDays, List<VisitSourceRecord> records) {
        StringBuilder sb = new StringBuilder(snapshotId + "|" + window + "|" + lateDays);
        records.stream()
                .sorted(Comparator.comparing(r -> r.id.toString()))
                .forEach(r -> sb.append('|').append(r.id).append(':').append(r.revision)
                        .append(':').append(r.referralVerified ? "V" : "U"));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<VisitDataset> datasets() {
        return datasetRepo.findAllByOrderByDatasetVersionDesc();
    }

    @Transactional(readOnly = true)
    public VisitDataset get(UUID datasetId) {
        return datasetRepo.findById(datasetId)
                .orElseThrow(() -> new NotFoundException("就诊数据集不存在: " + datasetId));
    }

    @Transactional(readOnly = true)
    public VisitDataset latest() {
        return datasetRepo.findFirstByOrderByDatasetVersionDesc()
                .orElseThrow(() -> new NotFoundException("尚无就诊数据集，请先编译"));
    }

    @Transactional(readOnly = true)
    public List<VisitDatasetRecord> recordsOf(UUID datasetId) {
        get(datasetId);
        return datasetRecordRepo.findByDatasetId(datasetId);
    }

    @Transactional(readOnly = true)
    public List<VisitEvent> eventsOf(UUID datasetId, String status, UUID masterId) {
        get(datasetId);
        List<VisitEvent> events;
        if (status != null && !status.isBlank()) {
            events = eventRepo.findByDatasetIdAndStatus(datasetId,
                    VisitEvent.Status.valueOf(status.toUpperCase()));
        } else {
            events = eventRepo.findByDatasetIdOrderByEventKey(datasetId);
        }
        if (masterId != null) {
            events = events.stream().filter(e -> masterId.equals(e.masterId)).toList();
        }
        return events;
    }

    @Transactional(readOnly = true)
    public List<VisitEventMember> membersOf(UUID eventId) {
        return memberRepo.findByEventIdOrderByOrdinal(eventId);
    }

    @Transactional(readOnly = true)
    public List<VisitPathway> pathwaysOf(UUID datasetId, UUID masterId, String status) {
        get(datasetId);
        if (masterId != null) {
            return pathwayRepo.findByDatasetIdAndMasterId(datasetId, masterId);
        }
        if (status != null && !status.isBlank()) {
            return pathwayRepo.findByDatasetIdAndStatus(datasetId,
                    VisitPathway.Status.valueOf(status.toUpperCase()));
        }
        return pathwayRepo.findByDatasetId(datasetId);
    }

    @Transactional(readOnly = true)
    public List<VisitEpisode> episodesOf(UUID datasetId, UUID masterId) {
        get(datasetId);
        if (masterId != null) {
            return episodeRepo.findByDatasetIdAndMasterId(datasetId, masterId);
        }
        return episodeRepo.findByDatasetIdOrderByEpisodeKey(datasetId);
    }

    @Transactional(readOnly = true)
    public List<VisitLabInterval> labIntervalsOf(UUID datasetId, UUID masterId) {
        get(datasetId);
        if (masterId != null) {
            return labIntervalRepo.findByDatasetIdAndMasterId(datasetId, masterId);
        }
        return labIntervalRepo.findByDatasetId(datasetId);
    }

    @Transactional(readOnly = true)
    public List<VisitCenterStats> centerStatsOf(UUID datasetId) {
        get(datasetId);
        return centerStatsRepo.findByDatasetIdOrderByCampusCode(datasetId);
    }

    @Transactional(readOnly = true)
    public List<VisitQualityFinding> qualityOf(UUID datasetId, String status) {
        get(datasetId);
        if (status != null && !status.isBlank()) {
            return findingRepo.findByDatasetIdAndStatus(datasetId,
                    VisitQualityFinding.Status.valueOf(status.toUpperCase()));
        }
        return findingRepo.findByDatasetIdOrderByCreatedAt(datasetId);
    }

    /** 人工确认 / 忽略质量审查条目（工作流状态，不改变数据集版本内容）。 */
    @Transactional
    public VisitQualityFinding ackFinding(UUID findingId, boolean dismiss, String note, String operatorId) {
        VisitQualityFinding finding = findingRepo.findById(findingId)
                .orElseThrow(() -> new NotFoundException("质量审查条目不存在: " + findingId));
        if (finding.status != VisitQualityFinding.Status.PENDING) {
            throw new ConflictException("质量审查条目状态为 " + finding.status + "，只有 PENDING 可以处理");
        }
        finding.status = dismiss
                ? VisitQualityFinding.Status.DISMISSED : VisitQualityFinding.Status.ACKED;
        finding.ackedAt = Instant.now();
        finding.ackedBy = operatorId;
        finding.ackNote = note;
        return findingRepo.save(finding);
    }

    // ------------------------------------------------------------------
    // 一次编译
    // ------------------------------------------------------------------

    private static final class Work {
        final VisitSourceRecord rec;
        UUID identityId;
        UUID masterId;
        boolean resolved;
        boolean duplicate;
        VisitDatasetRecord.Classification classification = VisitDatasetRecord.Classification.UNRESOLVED_IDENTITY;
        UUID eventId;
        String membership = "NONE";
        String note;

        Work(VisitSourceRecord rec) {
            this.rec = rec;
        }

        String key() {
            return rec.sourceSystem + "/" + rec.sourceRecordId;
        }

        boolean dateOnly() {
            return rec.timePrecision == VisitSourceRecord.TimePrecision.DATE_ONLY;
        }

        /** 急诊与其他服务分开成轨，避免同日独立急诊被并入门诊 */
        String track() {
            return "EMERGENCY".equalsIgnoreCase(rec.serviceType) ? "EMERGENCY" : "DEFAULT";
        }
    }

    private static final class Ev {
        VisitEvent entity = new VisitEvent();
        final List<Work> members = new ArrayList<>();
        final Set<String> memberCampuses = new LinkedHashSet<>();
        VisitEvent.Status status;
        String rule;
        String pendingReason;
        final List<String> evidence = new ArrayList<>();
        boolean pendingReview;

        boolean pending() {
            return pendingReview;
        }
    }

    private final class Compilation {
        private final VisitDataset ds;
        private final List<SnapshotMapping> mappings;
        private final List<VisitSourceRecord> rawRecords;

        private final List<Work> works = new ArrayList<>();
        private final List<Ev> events = new ArrayList<>();
        private final List<VisitPathway> pathways = new ArrayList<>();
        private final List<VisitQualityFinding> findings = new ArrayList<>();
        private final Map<UUID, Ev> eventById = new HashMap<>();

        Compilation(VisitDataset ds, List<SnapshotMapping> mappings, List<VisitSourceRecord> rawRecords) {
            this.ds = ds;
            this.mappings = mappings;
            this.rawRecords = rawRecords;
        }

        void run() {
            phaseIdentity();
            phaseDedup();
            phaseCompile();
            phasePaths();
            phaseStatsAndQuality();
            ds.completedAt = Instant.now();
            datasetRepo.save(ds);
        }

        // ---- 阶段 1：身份快照引用 ----
        private void phaseIdentity() {
            Map<String, SnapshotMapping> bySource = new HashMap<>();
            for (SnapshotMapping m : mappings) {
                bySource.put(m.sourceSystem + "/" + m.sourcePatientId, m);
            }
            for (VisitSourceRecord r : rawRecords) {
                Work w = new Work(r);
                SnapshotMapping m = bySource.get(r.sourceSystem + "/" + r.sourcePatientId);
                w.resolved = m != null;
                if (m != null) {
                    w.identityId = m.identityId;
                    w.masterId = m.masterId;
                    w.membership = "RESOLVED";
                } else {
                    w.note = "来源身份 " + r.sourceSystem + "/" + r.sourcePatientId
                            + " 不在所引用快照中（身份尚未确定），无法归并";
                    addFinding(VisitQualityFinding.Category.UNRESOLVED_IDENTITY,
                            VisitQualityFinding.Severity.WARNING, r, null, null, w.note);
                }
                works.add(w);
            }
            ds.totalRecords = works.size();
            ds.unresolvedRecords = (int) works.stream().filter(w -> !w.resolved).count();
            ds.resolvedRecords = works.size() - ds.unresolvedRecords;
            ds.identityStatus = ds.unresolvedRecords > 0
                    ? VisitDataset.PhaseStatus.PARTIAL : VisitDataset.PhaseStatus.COMPLETED;
        }

        // ---- 阶段 2：来源去重 ----
        private void phaseDedup() {
            Map<String, List<Work>> byFingerprint = new LinkedHashMap<>();
            for (Work w : works) {
                if (!w.resolved) {
                    continue;
                }
                // 指纹不含精确时间：同一检验在 10 分钟内重复回传（记录号不同、时间略有差异）
                // 才会被判定可能重复；不同日期或超过窗口的相同检验不会被误判
                String fp = String.join("|", w.rec.sourceSystem, w.rec.sourcePatientId,
                        w.rec.eventType.name(), nz(w.rec.serviceType), nz(w.rec.campusCode),
                        nz(w.rec.serviceDetail));
                byFingerprint.computeIfAbsent(fp, k -> new ArrayList<>()).add(w);
            }
            int duplicates = 0;
            for (List<Work> group : byFingerprint.values()) {
                if (group.size() < 2) {
                    continue;
                }
                group.sort(recordOrder());
                Work anchor = group.get(0);
                for (int i = 1; i < group.size(); i++) {
                    Work candidate = group.get(i);
                    if (candidate.rec.sourceSystem.equals(anchor.rec.sourceSystem)
                            && candidate.rec.sourceRecordId.equals(anchor.rec.sourceRecordId)) {
                        continue; // 同一流水不可能重复成行（接入层幂等）
                    }
                    if (!withinMinutes(anchor.rec.occurredAt, candidate.rec.occurredAt,
                            DUPLICATE_WINDOW_MINUTES)) {
                        continue;
                    }
                    candidate.duplicate = true;
                    candidate.classification = VisitDatasetRecord.Classification.POSSIBLE_DUPLICATE;
                    candidate.membership = "DEDUPED";
                    candidate.note = "与同源记录 " + anchor.key() + " 业务指纹一致且时间相差 ≤"
                            + DUPLICATE_WINDOW_MINUTES + " 分钟，可能为重复回传（保留原记录并从计数中剔除）";
                    duplicates++;
                    addFinding(VisitQualityFinding.Category.DUPLICATE_RECORD,
                            VisitQualityFinding.Severity.WARNING, candidate.rec, null, null, candidate.note);
                }
            }
            for (Work w : works) {
                if (w.rec.reuploadCount > 0) {
                    addFinding(VisitQualityFinding.Category.EXACT_REUPLOAD,
                            VisitQualityFinding.Severity.INFO, w.rec, null, null,
                            "来源记录 " + w.key() + " 同内容重复回传 " + w.rec.reuploadCount
                                    + " 次，已按一条记录计算");
                }
            }
            ds.dedupStatus = VisitDataset.PhaseStatus.COMPLETED;
            ds.possibleDuplicateRecords = duplicates;
        }

        // ---- 阶段 3：事件编译 ----
        private void phaseCompile() {
            List<Work> active = works.stream()
                    .filter(w -> w.resolved && !w.duplicate)
                    .collect(Collectors.toList());

            byEncounterKey(active);
            byTimeWindow(active);
            byDateOnly(active);
            markOverlappingProbableAsAmbiguous();

            ds.compileStatus = VisitDataset.PhaseStatus.COMPLETED;
        }

        /** 3.1 凭据优先：同一主体 + 同一就诊流水 → 一个事件 */
        private void byEncounterKey(List<Work> active) {
            Map<String, List<Work>> byEncounter = new TreeMap<>();
            for (Work w : active) {
                if (nonBlank(w.rec.encounterKey)) {
                    byEncounter.computeIfAbsent(w.masterId + "|" + w.rec.encounterKey,
                            k -> new ArrayList<>()).add(w);
                }
            }
            for (List<Work> group : byEncounter.values()) {
                group.sort(recordOrder());
                boolean dateOnly = group.stream().allMatch(Work::dateOnly);
                Ev ev = createEvent(group, dateOnly
                                ? VisitEvent.Status.AMBIGUOUS_TIME : VisitEvent.Status.CONFIRMED,
                        "ENCOUNTER_KEY", "ENCOUNTER_KEY",
                        dateOnly ? "凭据明确但只有日期级时间，仍属待定" : "同一就诊流水凭据");
                Set<String> registrationCampuses = group.stream()
                        .filter(w -> w.rec.eventType == VisitSourceRecord.EventType.REGISTRATION
                                || w.rec.eventType == VisitSourceRecord.EventType.CHECK_IN)
                        .map(w -> w.rec.campusCode).filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                if (registrationCampuses.size() > 1) {
                    addFinding(VisitQualityFinding.Category.CROSS_CAMPUS_WITHIN_ENCOUNTER,
                            VisitQualityFinding.Severity.WARNING, group.get(0).rec, ev.entity.id, null,
                            "同一就诊凭据出现在多个院区：" + registrationCampuses + "，请核对是否重复建档");
                }
                events.add(ev);
            }
        }

        /** 3.2 无凭据：同院同轨同日 + 时间窗聚类（急诊单独成轨，不与门诊混合） */
        private void byTimeWindow(List<Work> active) {
            List<Work> pool = new ArrayList<>();
            for (Work w : active) {
                if (!nonBlank(w.rec.encounterKey) && !w.dateOnly()) {
                    pool.add(w);
                }
            }
            pool.sort(recordOrder());
            Map<String, List<Work>> byCluster = new LinkedHashMap<>();
            for (Work w : pool) {
                String key = w.masterId + "|" + nz(w.rec.campusCode) + "|" + w.track() + "|" + dayOf(w);
                byCluster.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
            }
            for (List<Work> group : byCluster.values()) {
                group.sort(recordOrder());
                for (List<Work> cluster : splitByTimeWindow(group)) {
                    // 含转诊单号的无凭据记录优先视为"转诊目标侧"，聚合后由转诊链升级
                    boolean hasReferral = cluster.stream()
                            .anyMatch(w -> nonBlank(w.rec.referralKey));
                    Ev ev = createEvent(cluster,
                            hasReferral ? VisitEvent.Status.PROBABLE : VisitEvent.Status.PROBABLE,
                            hasReferral ? "REFERRAL_TARGET" : "TIME_WINDOW",
                            "PROBABLE_TIME_WINDOW",
                            (hasReferral ? "转诊目标侧缺少就诊流水凭据，" : "缺少就诊流水/转诊凭据，")
                                    + "按院区 + 服务轨道 + " + ds.timeWindowMinutes + " 分钟时间窗聚合");
                    events.add(ev);
                    addFinding(VisitQualityFinding.Category.TIME_WINDOW_MERGE,
                            VisitQualityFinding.Severity.INFO, cluster.get(0).rec, ev.entity.id, null,
                            "事件 " + ev.entity.eventKey + " 仅由时间窗聚合（" + cluster.size()
                                    + " 条记录），无明确凭据，不计入确定复诊");
                }
            }
        }

        /** 3.3 只有日期级时间的记录：明确进入待定类别，不放入时间窗 */
        private void byDateOnly(List<Work> active) {
            Map<String, List<Work>> byDate = new TreeMap<>();
            for (Work w : active) {
                if (w.dateOnly()) {
                    // 只按"主体 + 业务日期"分组：日期级记录无法区分同日多个地点是否同一次就诊，
                    // 合并为一组待定比拆成多次就诊更保守（不虚增复诊次数）
                    String key = w.masterId + "|" + dayOf(w);
                    byDate.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
                }
            }
            for (List<Work> group : byDate.values()) {
                group.sort(recordOrder());
                Ev ev = createEvent(group, VisitEvent.Status.AMBIGUOUS_TIME, "DATE_ONLY",
                        "DATE_ONLY", "时间戳只有日期，无法定位到具体就诊事件");
                events.add(ev);
                for (Work w : group) {
                    addFinding(VisitQualityFinding.Category.DATE_ONLY_TIME,
                            VisitQualityFinding.Severity.WARNING, w.rec, ev.entity.id, null,
                            "记录 " + w.key() + " 仅提供日期 " + w.rec.occurredDate
                                    + "，归入待定事件 " + ev.entity.eventKey);
                }
            }
        }

        /**
         * 无凭据时间窗事件若与同日同院确定事件时间相近，无法判断它属于该次就诊还是独立一次，
         * 转入"归属待定"，避免重复计数。
         */
        private void markOverlappingProbableAsAmbiguous() {
            List<Ev> confirmed = events.stream()
                    .filter(e -> e.status == VisitEvent.Status.CONFIRMED).toList();
            for (Ev p : events) {
                if (p.status != VisitEvent.Status.PROBABLE) {
                    continue;
                }
                for (Ev c : confirmed) {
                    if (!Objects.equals(p.entity.masterId, c.entity.masterId)
                            || !Objects.equals(p.entity.campusCode, c.entity.campusCode)
                            || p.entity.startAt == null || c.entity.startAt == null
                            || !sameDay(p.entity.startAt, c.entity.startAt)) {
                        continue;
                    }
                    long gap = Math.abs(Duration.between(p.entity.startAt, c.entity.startAt).toMinutes());
                    if (gap > ds.timeWindowMinutes) {
                        continue;
                    }
                    p.status = VisitEvent.Status.AMBIGUOUS_TIME;
                    p.pendingReason = "无凭据时间窗事件与确定事件 " + c.entity.eventKey
                            + " 同日同院且时间相近，无法判断归属";
                    p.entity.status = p.status;
                    for (Work w : p.members) {
                        w.classification = VisitDatasetRecord.Classification.AMBIGUOUS_MEMBERSHIP;
                        w.membership = "AMBIGUOUS_MEMBERSHIP";
                        w.note = p.pendingReason;
                        addFinding(VisitQualityFinding.Category.AMBIGUOUS_MEMBERSHIP,
                                VisitQualityFinding.Severity.WARNING, w.rec, p.entity.id, null, w.note);
                    }
                    break;
                }
            }
        }

        private Ev createEvent(List<Work> group, VisitEvent.Status status, String mergeRule,
                               String membership, String reason) {
            Ev ev = new Ev();
            ev.entity.id = UUID.randomUUID();
            ev.status = status;
            ev.rule = mergeRule;
            ev.entity.datasetId = ds.id;
            ev.entity.masterId = group.get(0).masterId;
            ev.entity.status = status;
            ev.entity.timePrecision = group.stream().allMatch(Work::dateOnly)
                    ? VisitSourceRecord.TimePrecision.DATE_ONLY
                    : VisitSourceRecord.TimePrecision.EXACT;
            ev.evidence.add(reason);
            Instant start = null;
            Instant end = null;
            for (Work w : group) {
                ev.members.add(w);
                w.eventId = ev.entity.id;
                w.classification = switch (status) {
                    case CONFIRMED -> VisitDatasetRecord.Classification.CONFIRMED_EVENT;
                    case PROBABLE -> VisitDatasetRecord.Classification.PROBABLE_EVENT;
                    case AMBIGUOUS_TIME -> VisitDatasetRecord.Classification.AMBIGUOUS_TIME_EVENT;
                };
                w.membership = membership;
                ev.memberCampuses.add(w.rec.campusCode);
                Instant at = w.rec.occurredAt;
                if (at == null && w.rec.occurredDate != null) {
                    at = w.rec.occurredDate.atStartOfDay().toInstant(ZoneOffset.UTC);
                }
                if (at != null) {
                    if (start == null || at.isBefore(start)) {
                        start = at;
                    }
                    if (end == null || at.isAfter(end)) {
                        end = at;
                    }
                }
            }
            ev.entity.startAt = start;
            ev.entity.endAt = end;
            ev.entity.occurredDate = group.get(0).rec.occurredDate;
            ev.entity.memberCount = group.size();
            ev.entity.campusCode = anchorCampus(group);
            ev.entity.serviceType = dominantServiceType(group);
            return ev;
        }

        /**
         * 事件锚点院区：取最早挂号 / 签到记录所在院区；没有挂号记录时取最早记录院区。
         * 采血 / 影像记录随就诊流水并入门诊事件，不单独形成一次就诊。
         */
        private String anchorCampus(List<Work> group) {
            return group.stream()
                    .filter(w -> w.rec.eventType == VisitSourceRecord.EventType.REGISTRATION
                            || w.rec.eventType == VisitSourceRecord.EventType.CHECK_IN)
                    .min(recordOrder())
                    .map(w -> w.rec.campusCode)
                    .orElseGet(() -> group.stream().min(recordOrder())
                            .map(w -> w.rec.campusCode).orElse("UNKNOWN"));
        }

        private String dominantServiceType(List<Work> group) {
            return group.stream()
                    .filter(w -> !"EMERGENCY".equalsIgnoreCase(w.rec.serviceType))
                    .min(recordOrder())
                    .map(w -> w.rec.serviceType)
                    .orElse(group.get(0).rec.serviceType);
        }

        private List<List<Work>> splitByTimeWindow(List<Work> sorted) {
            List<List<Work>> clusters = new ArrayList<>();
            List<Work> current = new ArrayList<>();
            for (Work w : sorted) {
                if (!current.isEmpty()) {
                    Instant prev = current.get(current.size() - 1).rec.occurredAt;
                    Instant next = w.rec.occurredAt;
                    if (prev != null && next != null
                            && Duration.between(prev, next).toMinutes() > ds.timeWindowMinutes) {
                        clusters.add(current);
                        current = new ArrayList<>();
                    }
                }
                current.add(w);
            }
            if (!current.isEmpty()) {
                clusters.add(current);
            }
            return clusters;
        }

        // ---- 阶段 4：转诊链、跨院路径与诊疗过程 ----
        private void phasePaths() {
            orderAndPersistEvents();
            upgradeProbableTargetsByReferral();
            buildReferralPathways();
            buildAdjacentPathways();
            persistPathways();
            buildEpisodes();
            ds.pathStatus = VisitDataset.PhaseStatus.COMPLETED;
        }

        private void orderAndPersistEvents() {
            events.sort(Comparator
                    .comparing((Ev e) -> e.entity.masterId.toString())
                    .thenComparing(e -> e.entity.startAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(e -> e.entity.campusCode));
            int seq = 0;
            for (Ev ev : events) {
                ev.entity.eventKey = String.format("E%04d", ++seq);
                eventRepo.save(ev.entity);
                eventById.put(ev.entity.id, ev);
                for (int i = 0; i < ev.members.size(); i++) {
                    Work w = ev.members.get(i);
                    VisitEventMember m = new VisitEventMember();
                    m.datasetId = ds.id;
                    m.eventId = ev.entity.id;
                    m.recordId = w.rec.id;
                    m.sourceSystem = w.rec.sourceSystem;
                    m.sourceRecordId = w.rec.sourceRecordId;
                    m.eventType = w.rec.eventType.name();
                    m.serviceType = w.rec.serviceType;
                    m.campusCode = w.rec.campusCode;
                    m.occurredAt = w.rec.occurredAt;
                    m.mergeRule = ev.rule;
                    m.membershipStatus = w.membership;
                    m.note = w.note;
                    m.ordinal = i + 1;
                    memberRepo.save(m);
                }
            }
        }

        /**
         * 转诊单本身是凭据：若目标侧记录只靠时间窗聚成"可能事件"，
         * 凭转诊单把该事件升级为确定事件（服务链语义）。
         */
        private void upgradeProbableTargetsByReferral() {
            for (Work ref : works) {
                if (!ref.resolved || ref.duplicate || !nonBlank(ref.rec.referralKey)
                        || !nonBlank(ref.rec.referralTargetCampus)) {
                    continue;
                }
                for (Ev ev : events) {
                    if (ev.status != VisitEvent.Status.PROBABLE) {
                        continue;
                    }
                    boolean matched = ev.members.stream().anyMatch(m ->
                            ref.rec.referralKey.equals(m.rec.referralKey)
                                    && ref.rec.referralTargetCampus.equals(m.rec.campusCode));
                    if (matched) {
                        ev.status = VisitEvent.Status.CONFIRMED;
                        ev.rule = "REFERRAL_CHAIN";
                        ev.entity.status = ev.status;
                        ev.evidence.add("转诊单 " + ref.rec.referralKey + " 提供关联凭据，事件升级为确定");
                        for (Work m : ev.members) {
                            m.classification = VisitDatasetRecord.Classification.CONFIRMED_EVENT;
                            m.membership = "REFERRAL_CHAIN";
                            m.note = "转诊单 " + ref.rec.referralKey + " 关联，确定为一次就诊";
                        }
                        eventRepo.save(ev.entity);
                    }
                }
            }
        }

        /** 依据转诊单连接前后两次就诊。 */
        private void buildReferralPathways() {
            Set<String> linkedPairs = new HashSet<>();
            for (Work ref : works) {
                if (!ref.resolved || ref.duplicate || !nonBlank(ref.rec.referralKey)
                        || !nonBlank(ref.rec.referralTargetCampus) || ref.eventId == null) {
                    continue;
                }
                Ev from = eventById.get(ref.eventId);
                if (from == null) {
                    continue;
                }
                Ev to = null;
                for (Ev ev : events) {
                    if (ev == from || !Objects.equals(ev.entity.masterId, from.entity.masterId)) {
                        continue;
                    }
                    boolean target = ev.members.stream().anyMatch(m ->
                            ref.rec.referralKey.equals(m.rec.referralKey)
                                    && ref.rec.referralTargetCampus.equals(m.rec.campusCode));
                    if (target && !ev.pending()) {
                        to = ev;
                        break;
                    }
                }
                if (to == null || Objects.equals(from.entity.campusCode, to.entity.campusCode)) {
                    continue;
                }
                boolean late = isLateReferral(ref);
                VisitPathway pw = newPathway(from, to);
                pw.type = VisitPathway.Type.CROSS_CAMPUS_REFERRAL;
                pw.referralRecordId = ref.rec.id;
                pw.referralKey = ref.rec.referralKey;
                if (late && !ref.rec.referralVerified) {
                    pw.status = VisitPathway.Status.LATE_PENDING;
                    pw.evidence = "转诊单晚到（开出后 "
                            + Duration.between(occurredAtOrDate(ref.rec), ref.rec.receivedAt).toDays()
                            + " 天到达 > " + ds.lateReferralDays + " 天阈值），需人工核验后才计入确定路径";
                    // 只把"目标侧就诊"挂起：转诊若核实，A→B 属同一诊疗过程；
                    // 核实前不把 B 单独计为一次复诊（不强行增加），也不删除既有记录
                    markPending(to, "转诊单 " + ref.rec.referralKey + " 晚到，该次就诊是否独立待核验");
                    addFinding(VisitQualityFinding.Category.LATE_REFERRAL,
                            VisitQualityFinding.Severity.WARNING, ref.rec, to.entity.id, pw.id, pw.evidence);
                } else {
                    pw.status = VisitPathway.Status.CONFIRMED;
                    pw.evidence = ref.rec.referralVerified
                            ? "转诊单 " + ref.rec.referralKey + " 已人工核验，确认跨院转诊"
                            : "转诊单 " + ref.rec.referralKey + " 关联前后两次就诊，确认为同一诊疗过程";
                }
                pathways.add(pw);
                linkedPairKeys.add(pairKey(from, to));
            }
        }

        /** 相邻确定事件之间的路径：同院返院 / 无凭据跨院待定 / 远期独立跨院就诊。 */
        private void buildAdjacentPathways() {
            Map<UUID, List<Ev>> byMaster = events.stream()
                    .filter(e -> e.status == VisitEvent.Status.CONFIRMED || e.pending())
                    .collect(Collectors.groupingBy(e -> e.entity.masterId, LinkedHashMap::new,
                            Collectors.toList()));
            for (List<Ev> list : byMaster.values()) {
                list.sort(Comparator.comparing(e -> e.entity.startAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
                for (int i = 0; i + 1 < list.size(); i++) {
                    Ev from = list.get(i);
                    Ev to = list.get(i + 1);
                    if (from.entity.startAt == null || to.entity.startAt == null
                            || linkedPairKeys.contains(pairKey(from, to))) {
                        continue;
                    }
                    // 任一端已因转诊晚到 / 跨院缺凭据进入待定：相邻路径同样待定，不并入确定路径
                    if (from.pendingReview || to.pendingReview) {
                        continue;
                    }
                    VisitPathway pw = newPathway(from, to);
                    boolean sameCampus = Objects.equals(from.entity.campusCode, to.entity.campusCode);
                    long gap = pw.gapMinutes;
                    if (sameCampus) {
                        pw.type = VisitPathway.Type.SAME_CAMPUS_RETURN;
                        pw.status = VisitPathway.Status.CONFIRMED;
                        pw.evidence = "同院两次独立就诊凭据，计为一次复诊";
                    } else if (Math.abs(gap) <= TRANSFER_WINDOW_HOURS * 60L) {
                        pw.type = VisitPathway.Type.UNVERIFIED_TRANSFER;
                        pw.status = VisitPathway.Status.UNVERIFIED;
                        pw.evidence = "跨院相邻就诊间隔 " + gap
                                + " 分钟，但缺少转诊凭据：可能为转院重复记录，也可能为独立返院，进入待定";
                        // 只挂起后一次就诊：前一次已确认；后一次可能是同一就诊的重复记录，
                        // 核实前不计为一次新的复诊
                        markPending(to, "紧邻前次跨院就诊且无转诊凭据，可能为同一次就诊的重复记录");
                        addFinding(VisitQualityFinding.Category.UNVERIFIED_TRANSFER,
                                VisitQualityFinding.Severity.WARNING, null, to.entity.id, pw.id,
                                pw.evidence);
                    } else {
                        pw.type = VisitPathway.Type.CROSS_CAMPUS_VISIT;
                        pw.status = VisitPathway.Status.CONFIRMED;
                        pw.evidence = "跨院相隔 " + gap + " 分钟，超过转院窗口，判定为独立跨院就诊";
                    }
                    pathways.add(pw);
                }
            }
        }

        private final Set<String> linkedPairKeys = new HashSet<>();

        private String pairKey(Ev a, Ev b) {
            return a.entity.id + "->" + b.entity.id;
        }

        private VisitPathway newPathway(Ev from, Ev to) {
            VisitPathway pw = new VisitPathway();
            pw.id = UUID.randomUUID();
            pw.datasetId = ds.id;
            pw.masterId = from.entity.masterId;
            pw.fromEventId = from.entity.id;
            pw.toEventId = to.entity.id;
            pw.fromCampus = from.entity.campusCode;
            pw.toCampus = to.entity.campusCode;
            pw.gapMinutes = Duration.between(from.entity.startAt, to.entity.startAt).toMinutes();
            return pw;
        }

        private void markPending(Ev ev, String reason) {
            ev.pendingReview = true;
            if (ev.pendingReason == null) {
                ev.pendingReason = reason;
            }
        }

        private void persistPathways() {
            for (VisitPathway pw : pathways) {
                pathwayRepo.save(pw);
            }
        }

        /** 诊疗过程：只连接"确定且未待定"的事件；待定事件不参与复诊次数。 */
        private void buildEpisodes() {
            List<Ev> eligible = events.stream()
                    .filter(e -> e.status == VisitEvent.Status.CONFIRMED)
                    .filter(e -> !e.pendingReview)
                    .filter(e -> e.entity.startAt != null)
                    .sorted(Comparator.comparing(e -> e.entity.startAt))
                    .toList();
            Map<UUID, UUID> parent = new HashMap<>();
            for (Ev e : eligible) {
                parent.put(e.entity.id, e.entity.id);
            }
            for (VisitPathway pw : pathways) {
                if (pw.type == VisitPathway.Type.CROSS_CAMPUS_REFERRAL
                        && pw.status == VisitPathway.Status.CONFIRMED
                        && parent.containsKey(pw.fromEventId) && parent.containsKey(pw.toEventId)) {
                    union(parent, pw.fromEventId, pw.toEventId);
                }
            }
            Map<UUID, List<Ev>> groups = new LinkedHashMap<>();
            for (Ev e : eligible) {
                groups.computeIfAbsent(find(parent, e.entity.id), k -> new ArrayList<>()).add(e);
            }
            int seq = 0;
            for (List<Ev> evs : groups.values()) {
                evs.sort(Comparator.comparing(e -> e.entity.startAt));
                VisitEpisode ep = new VisitEpisode();
                ep.datasetId = ds.id;
                ep.masterId = evs.get(0).entity.masterId;
                ep.episodeKey = String.format("EP%04d", ++seq);
                ep.startAt = evs.get(0).entity.startAt;
                ep.endAt = evs.get(evs.size() - 1).entity.endAt;
                ep.eventCount = evs.size();
                Set<String> anchors = new LinkedHashSet<>();
                Set<String> memberCampuses = new LinkedHashSet<>();
                for (Ev ev : evs) {
                    anchors.add(ev.entity.campusCode);
                    memberCampuses.addAll(ev.memberCampuses);
                    ev.entity.episodeKey = ep.episodeKey;
                    eventRepo.save(ev.entity);
                }
                // 过程覆盖的院区（含采血 / 影像等参与中心）用于各中心复诊统计
                ep.campuses = String.join(",", memberCampuses);
                // 跨院以"就诊锚点院区"多寡判定，避免采血 / 影像参与被误判为跨院
                ep.campusCount = anchors.size();
                ep.confirmed = true;
                episodeRepo.save(ep);
                ds.confirmedEpisodes++;
                if (anchors.size() > 1) {
                    ds.crossCampusEpisodes++;
                }
            }
        }

        private UUID find(Map<UUID, UUID> parent, UUID x) {
            while (!parent.get(x).equals(x)) {
                parent.put(x, parent.get(parent.get(x)));
                x = parent.get(x);
            }
            return x;
        }

        private void union(Map<UUID, UUID> parent, UUID a, UUID b) {
            UUID ra = find(parent, a);
            UUID rb = find(parent, b);
            if (!ra.equals(rb)) {
                parent.put(rb, ra);
            }
        }

        // ---- 阶段 5：统计与质量审查 ----
        private void phaseStatsAndQuality() {
            persistDatasetRecords();
            countEvents();
            computeLabIntervals();
            computeCenterStats();
            for (VisitQualityFinding f : findings) {
                findingRepo.save(f);
            }
            ds.qualityFindingCount = findings.size();
            ds.qualityStatus = VisitDataset.PhaseStatus.COMPLETED;
        }

        private void persistDatasetRecords() {
            int ambiguous = 0;
            int probable = 0;
            int pending = 0;
            for (Work w : works) {
                VisitDatasetRecord dr = new VisitDatasetRecord();
                dr.datasetId = ds.id;
                dr.recordId = w.rec.id;
                dr.identityId = w.identityId;
                dr.masterId = w.masterId;
                dr.identityStatus = w.resolved
                        ? VisitDatasetRecord.IdentityStatus.RESOLVED
                        : VisitDatasetRecord.IdentityStatus.UNRESOLVED;
                dr.dedupStatus = w.duplicate
                        ? VisitDatasetRecord.DedupStatus.POSSIBLE_DUPLICATE
                        : VisitDatasetRecord.DedupStatus.UNIQUE;
                dr.classification = w.classification;
                // 事件确定但归属 / 路径待核验：记录进入明确的待定类别，避免被当作确定复诊
                if (w.classification == VisitDatasetRecord.Classification.CONFIRMED_EVENT
                        && w.eventId != null) {
                    Ev ev = eventById.get(w.eventId);
                    if (ev != null && ev.pendingReview) {
                        dr.classification = VisitDatasetRecord.Classification.PENDING_EVENT;
                    }
                }
                dr.eventId = w.eventId;
                dr.membership = w.membership;
                dr.note = w.note;
                dr.sourceSystem = w.rec.sourceSystem;
                dr.sourceRecordId = w.rec.sourceRecordId;
                dr.sourcePatientId = w.rec.sourcePatientId;
                dr.campusCode = w.rec.campusCode;
                dr.eventType = w.rec.eventType.name();
                dr.serviceType = w.rec.serviceType;
                dr.occurredAt = w.rec.occurredAt;
                datasetRecordRepo.save(dr);
                if (dr.classification == VisitDatasetRecord.Classification.AMBIGUOUS_TIME_EVENT
                        || dr.classification == VisitDatasetRecord.Classification.AMBIGUOUS_MEMBERSHIP) {
                    ambiguous++;
                }
                if (dr.classification == VisitDatasetRecord.Classification.PROBABLE_EVENT) {
                    probable++;
                }
            }
            ds.ambiguousRecords = ambiguous;
            ds.probableRecords = probable;
            ds.pendingReviewRecords = (int) works.stream()
                    .filter(w -> w.eventId != null)
                    .filter(w -> {
                        Ev ev = eventById.get(w.eventId);
                        return ev != null && ev.pendingReview;
                    })
                    .count();
            ds.unmergeableRecords = ds.unresolvedRecords + ambiguous;
        }

        private void countEvents() {
            ds.confirmedEvents = 0;
            ds.probableEvents = 0;
            ds.ambiguousTimeEvents = 0;
            ds.pendingReviewEvents = 0;
            for (Ev ev : events) {
                switch (ev.status) {
                    case CONFIRMED -> {
                        if (ev.pendingReview) {
                            ds.pendingReviewEvents++;
                        } else {
                            ds.confirmedEvents++;
                        }
                    }
                    case PROBABLE -> ds.probableEvents++;
                    case AMBIGUOUS_TIME -> ds.ambiguousTimeEvents++;
                }
                if (ev.pendingReview || ev.pendingReason != null) {
                    ev.entity.pendingReview = true;
                    ev.entity.pendingReason = ev.pendingReason;
                    eventRepo.save(ev.entity);
                }
            }
            ds.confirmedPathways = (int) pathways.stream()
                    .filter(p -> p.status == VisitPathway.Status.CONFIRMED).count();
            ds.latePendingPathways = (int) pathways.stream()
                    .filter(p -> p.status == VisitPathway.Status.LATE_PENDING).count();
            ds.unverifiedTransferPathways = (int) pathways.stream()
                    .filter(p -> p.status == VisitPathway.Status.UNVERIFIED).count();
        }

        /**
         * 检验复查间隔：同一主体、同一检验项目、两个不同确定性就诊事件之间的间隔。
         * 重复回传记录已在去重阶段排除，不会重复计算或缩短间隔。
         */
        private void computeLabIntervals() {
            Map<String, List<Work>> byTest = new LinkedHashMap<>();
            for (Ev ev : events) {
                if (ev.status != VisitEvent.Status.CONFIRMED || ev.pendingReview) {
                    continue;
                }
                for (Work w : ev.members) {
                    if (w.rec.eventType != VisitSourceRecord.EventType.LAB
                            && !"LAB".equalsIgnoreCase(w.rec.serviceType)) {
                        continue;
                    }
                    if (w.rec.occurredAt == null || w.rec.serviceDetail == null) {
                        continue;
                    }
                    byTest.computeIfAbsent(w.masterId + "|" + w.rec.serviceDetail,
                            k -> new ArrayList<>()).add(w);
                }
            }
            int count = 0;
            for (List<Work> list : byTest.values()) {
                list.sort(recordOrder());
                for (int i = 0; i + 1 < list.size(); i++) {
                    Work a = list.get(i);
                    Work b = list.get(i + 1);
                    if (a.eventId.equals(b.eventId)) {
                        continue; // 同一次就诊内的复测不算复查间隔
                    }
                    VisitLabInterval iv = new VisitLabInterval();
                    iv.datasetId = ds.id;
                    iv.masterId = a.masterId;
                    iv.testName = a.rec.serviceDetail;
                    iv.earlierEventId = a.eventId;
                    iv.laterEventId = b.eventId;
                    iv.earlierCampus = a.rec.campusCode;
                    iv.laterCampus = b.rec.campusCode;
                    iv.earlierAt = a.rec.occurredAt;
                    iv.laterAt = b.rec.occurredAt;
                    iv.intervalHours = Duration.between(a.rec.occurredAt, b.rec.occurredAt).toMinutes() / 60.0;
                    iv.crossCampus = !Objects.equals(a.rec.campusCode, b.rec.campusCode);
                    labIntervalRepo.save(iv);
                    count++;
                }
            }
            ds.labIntervalCount = count;
        }

        /**
         * 各中心统计：复诊按诊疗过程（episode）计数，不按来源记录或事件计数。
         * 同一次就诊里挂号、签到、采血、影像多条记录只产生 1 个 episode，
         * 因此各中心复诊率不会因记录粒度不同被虚假放大。
         */
        private void computeCenterStats() {
            List<VisitEpisode> episodes = episodeRepo.findByDatasetIdOrderByEpisodeKey(ds.id);

            Map<String, CenterAcc> stats = new TreeMap<>();
            for (Work w : works) {
                CenterAcc acc = stats.computeIfAbsent(nz(w.rec.campusCode), k -> new CenterAcc());
                acc.recordCount++;
                switch (w.classification) {
                    case CONFIRMED_EVENT -> acc.confirmedEventCount++;
                    case PROBABLE_EVENT -> acc.probableEventCount++;
                    case AMBIGUOUS_TIME_EVENT, AMBIGUOUS_MEMBERSHIP -> acc.ambiguousRecordCount++;
                    case POSSIBLE_DUPLICATE -> acc.duplicateRecordCount++;
                    case UNRESOLVED_IDENTITY -> acc.unmergeableRecordCount++;
                    case PENDING_EVENT -> { /* 待定事件按事件维度统计，避免记录粒度重复计数 */ }
                }
            }
            // 事件维度：锚点院区
            for (Ev ev : events) {
                CenterAcc acc = stats.computeIfAbsent(nz(ev.entity.campusCode), k -> new CenterAcc());
                if (ev.pendingReview) {
                    acc.pendingEventCount++;
                }
            }
            // 过程维度：只按锚点院区计一次
            Map<String, Map<UUID, Long>> episodesPerMaster = new TreeMap<>();
            for (VisitEpisode ep : episodes) {
                for (String campus : ep.campuses.split(",")) {
                    if (campus.isBlank()) {
                        continue;
                    }
                    CenterAcc acc = stats.computeIfAbsent(campus, k -> new CenterAcc());
                    acc.episodes++;
                    acc.patients.add(ep.masterId);
                    if (ep.campusCount > 1) {
                        acc.crossCampusEpisodes++;
                    }
                    episodesPerMaster.computeIfAbsent(campus, k -> new HashMap<>())
                            .merge(ep.masterId, 1L, Long::sum);
                }
            }
            for (Map.Entry<String, Map<UUID, Long>> entry : episodesPerMaster.entrySet()) {
                CenterAcc acc = stats.computeIfAbsent(entry.getKey(), k -> new CenterAcc());
                for (Long n : entry.getValue().values()) {
                    if (n >= 2) {
                        acc.patientsWithRecurrence++;
                        acc.recurrenceCount += (int) (n - 1);
                    }
                }
            }
            for (VisitLabInterval iv : labIntervalRepo.findByDatasetId(ds.id)) {
                if (iv.earlierCampus != null) {
                    stats.computeIfAbsent(iv.earlierCampus, k -> new CenterAcc()).labIntervalCount++;
                }
                if (iv.laterCampus != null && !iv.laterCampus.equals(iv.earlierCampus)) {
                    stats.computeIfAbsent(iv.laterCampus, k -> new CenterAcc()).labIntervalCount++;
                }
            }

            for (Map.Entry<String, CenterAcc> entry : stats.entrySet()) {
                CenterAcc acc = entry.getValue();
                VisitCenterStats cs = new VisitCenterStats();
                cs.datasetId = ds.id;
                cs.campusCode = entry.getKey();
                cs.recordCount = acc.recordCount;
                cs.confirmedEventCount = (int) events.stream()
                        .filter(e -> e.status == VisitEvent.Status.CONFIRMED
                                && entry.getKey().equals(nz(e.entity.campusCode))).count();
                cs.probableEventCount = (int) events.stream()
                        .filter(e -> e.status == VisitEvent.Status.PROBABLE
                                && entry.getKey().equals(nz(e.entity.campusCode))).count();
                cs.duplicateRecordCount = acc.duplicateRecordCount;
                cs.ambiguousRecordCount = acc.ambiguousRecordCount;
                cs.unmergeableRecordCount = acc.unmergeableRecordCount;
                cs.patients = acc.patients.size();
                cs.episodes = acc.episodes;
                cs.patientsWithRecurrence = acc.patientsWithRecurrence;
                cs.recurrenceCount = acc.recurrenceCount;
                cs.recurrenceRate = cs.patients == 0 ? 0.0
                        : Math.round(acc.patientsWithRecurrence * 10000.0 / cs.patients) / 10000.0;
                cs.crossCampusEpisodeCount = acc.crossCampusEpisodes;
                cs.pendingReviewEventCount = acc.pendingEventCount;
                cs.labIntervalCount = acc.labIntervalCount;
                centerStatsRepo.save(cs);
            }
        }

        private final class CenterAcc {
            int recordCount;
            int confirmedEventCount;
            int probableEventCount;
            int duplicateRecordCount;
            int ambiguousRecordCount;
            int unmergeableRecordCount;
            int pendingEventCount;
            int episodes;
            int patientsWithRecurrence;
            int recurrenceCount;
            int crossCampusEpisodes;
            int labIntervalCount;
            final Set<UUID> patients = new HashSet<>();
        }

        private void addFinding(VisitQualityFinding.Category category, VisitQualityFinding.Severity severity,
                                VisitSourceRecord record, UUID eventId, UUID pathwayId, String detail) {
            VisitQualityFinding f = new VisitQualityFinding();
            f.id = UUID.randomUUID();
            f.datasetId = ds.id;
            f.category = category;
            f.severity = severity;
            f.eventId = eventId;
            f.pathwayId = pathwayId;
            if (record != null) {
                f.recordId = record.id;
                f.sourceSystem = record.sourceSystem;
                f.sourceRecordId = record.sourceRecordId;
                f.campusCode = record.campusCode;
            }
            f.detail = detail;
            findings.add(f);
        }

        private Comparator<Work> recordOrder() {
            return Comparator
                    .comparing((Work w) -> w.rec.occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(w -> w.rec.occurredDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(w -> w.rec.sourceSystem)
                    .thenComparing(w -> w.rec.sourceRecordId);
        }

        private String dayOf(Work w) {
            if (w.rec.occurredAt != null) {
                return w.rec.occurredAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
            }
            if (w.rec.occurredDate != null) {
                return w.rec.occurredDate.toString();
            }
            return "UNKNOWN";
        }

        private boolean withinMinutes(Instant a, Instant b, int minutes) {
            return a != null && b != null
                    && Math.abs(Duration.between(a, b).toMinutes()) <= minutes;
        }

        private boolean sameDay(Instant a, Instant b) {
            return a.atZone(ZoneOffset.UTC).toLocalDate().equals(b.atZone(ZoneOffset.UTC).toLocalDate());
        }

        private Instant occurredAtOrDate(VisitSourceRecord r) {
            if (r.occurredAt != null) {
                return r.occurredAt;
            }
            return r.occurredDate == null ? r.receivedAt
                    : r.occurredDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        }

        private boolean isLateReferral(Work referral) {
            if (referral.rec.receivedAt == null) {
                return false;
            }
            return Duration.between(occurredAtOrDate(referral.rec), referral.rec.receivedAt)
                    .toDays() > ds.lateReferralDays;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
