package com.clinic.mpi.seed;

import com.clinic.mpi.domain.GraphState;
import com.clinic.mpi.domain.Snapshot;
import com.clinic.mpi.repo.GraphStateRepo;
import com.clinic.mpi.service.BizService;
import com.clinic.mpi.service.IdentityService;
import com.clinic.mpi.service.SnapshotService;
import com.clinic.mpi.service.VisitDatasetService;
import com.clinic.mpi.service.VisitRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 本地样例数据（幂等：已有图谱状态时跳过）：
 * <ul>
 *   <li>多院重复挂号：张三在院区A（REG_A/P-A-001）与院区B（REG_B/P-B-001）重复建档；</li>
 *   <li>证件更正：赵六在检验系统（LAB_A/L-A-001）证件号末位录入错误，待更正后匹配；</li>
 *   <li>家庭共用号码：李四与李四中（父子）共用手机号，评分中等，应人工拒绝合并；</li>
 *   <li>误并素材：两个同名"陈晨"仅姓名相同，供人工误并→拆分回放。</li>
 * </ul>
 * 同时播种初始快照与预约、结算（含已关账）、检验报告等下游记录，
 * 以及跨系统就诊记录五类验收样例与初始就诊数据集版本。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final GraphStateRepo graphStateRepo;
    private final IdentityService identityService;
    private final SnapshotService snapshotService;
    private final BizService bizService;
    private final VisitRecordService visitRecordService;
    private final VisitDatasetService visitDatasetService;

    public DataSeeder(GraphStateRepo graphStateRepo, IdentityService identityService,
                      SnapshotService snapshotService, BizService bizService,
                      VisitRecordService visitRecordService, VisitDatasetService visitDatasetService) {
        this.graphStateRepo = graphStateRepo;
        this.identityService = identityService;
        this.snapshotService = snapshotService;
        this.bizService = bizService;
        this.visitRecordService = visitRecordService;
        this.visitDatasetService = visitDatasetService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (graphStateRepo.count() > 0) {
            log.info("图谱已初始化，跳过样例数据播种");
            return;
        }
        log.info("播种本地样例数据...");
        graphStateRepo.save(new GraphState());

        // ---- 来源身份 ----
        // 场景1：多院重复挂号 —— 张三（同一人，两院区各自建档）
        identityService.ingest("REG_A", "P-A-001", "张三", "13800000001",
                "110101199001011234", "M", LocalDate.of(1990, 1, 1));
        identityService.ingest("REG_B", "P-B-001", "张三", "13800000001",
                "110101199001011234", "M", LocalDate.of(1990, 1, 1));

        // 场景2（误并素材）：两个同名"陈晨"，实际为不同人，仅姓名一致（评分低于阈值，不产生候选对）
        identityService.ingest("REG_A", "P-A-002", "陈晨", "13700000002",
                "110101198505054321", "F", LocalDate.of(1985, 5, 5));
        identityService.ingest("REG_B", "P-B-002", "陈晨", "13711110002",
                null, "F", LocalDate.of(1988, 8, 8));

        // 场景3：家庭共用号码 —— 李四与李四中（父子）共用手机号，评分中等，应人工拒绝
        identityService.ingest("REG_A", "P-A-003", "李四", "13900000003",
                "110101196001010011", "M", LocalDate.of(1960, 1, 1));
        identityService.ingest("REG_B", "P-B-003", "李四中", "13900000003",
                "110101198802020022", "M", LocalDate.of(1988, 2, 2));

        // 场景4：证件更正 —— 赵六在检验系统证件号末位录错（...110 应为 ...111）
        identityService.ingest("REG_A", "P-A-004", "赵六", "13600000004",
                "110101197703031111", "M", LocalDate.of(1977, 3, 3));
        identityService.ingest("LAB_A", "L-A-001", "赵六", "13600000004",
                "110101197703031110", "M", LocalDate.of(1977, 3, 3));
        identityService.ingest("SETTLE_A", "S-A-001", "赵六", "13600000004",
                "110101197703031111", "M", LocalDate.of(1977, 3, 3));

        // 场景5 就诊事件样例：跨院临床数据平台 HIS 的就诊人（证据字段互不相同，不产生候选对）
        identityService.ingest("HIS", "V-001", "孙七", "13500000001",
                "110101198001011111", "M", LocalDate.of(1980, 1, 1));
        identityService.ingest("HIS", "V-002", "周八", "13500000002",
                "110101198202022222", "M", LocalDate.of(1982, 2, 2));
        identityService.ingest("HIS", "V-003", "吴九", "13500000003",
                "110101198303033333", "F", LocalDate.of(1983, 3, 3));
        identityService.ingest("HIS", "V-004", "郑十", "13500000004",
                "110101198404044444", "M", LocalDate.of(1984, 4, 4));
        identityService.ingest("HIS", "V-005", "何十一", "13500000005",
                "110101198505055555", "F", LocalDate.of(1985, 5, 5));
        identityService.ingest("HIS", "V-006", "钱十二", "13500000006",
                "110101198606066666", "M", LocalDate.of(1986, 6, 6));
        identityService.ingest("HIS", "V-007", "冯十三", "13500000007",
                "110101198707077777", "F", LocalDate.of(1987, 7, 7));

        // ---- 初始业务快照（固定图谱版本 v0，全部身份各自为单例主体） ----
        Snapshot apptSnap = snapshotService.publish(
                Snapshot.BusinessType.APPOINTMENT, "SN-INIT-APPT 初始预约快照", "seed");
        Snapshot settleSnap = snapshotService.publish(
                Snapshot.BusinessType.SETTLEMENT, "SN-INIT-SETTLE 初始结算快照", "seed");
        Snapshot labSnap = snapshotService.publish(
                Snapshot.BusinessType.LAB, "SN-INIT-LAB 初始检验快照", "seed");

        // ---- 下游业务记录 ----
        // 陈晨B（REG_B/P-B-002）的预约：误并后提醒将错发，拆分后应恢复
        bizService.createAppointment(apptSnap.id, "REG_B", "P-B-002",
                Instant.now().plus(3, ChronoUnit.DAYS));
        // 李四中（REG_B/P-B-003）的预约：家庭共用号码场景，拒绝合并后提醒不变
        bizService.createAppointment(apptSnap.id, "REG_B", "P-B-003",
                Instant.now().plus(5, ChronoUnit.DAYS));

        // 陈晨A（REG_A/P-A-002）的已关账结算单：拆分后只生成调整清单，凭证不改写
        var closedSettlement = bizService.createSettlement(
                settleSnap.id, "REG_A", "P-A-002", new BigDecimal("880.00"), "2026-10");
        bizService.closeSettlement(closedSettlement.id);
        // 赵六（SETTLE_A/S-A-001）的未关账结算单：合并后归属可随确认快照更新
        bizService.createSettlement(settleSnap.id, "SETTLE_A", "S-A-001",
                new BigDecimal("1200.00"), "2026-11");

        // 检验报告：陈晨A 血常规、赵六（检验系统）尿常规，均未签发
        bizService.createLabReport(labSnap.id, "REG_A", "P-A-002", "血常规");
        bizService.createLabReport(labSnap.id, "LAB_A", "L-A-001", "尿常规");

        // ---- 跨系统就诊记录与就诊数据集 ----
        Snapshot visitSnap = snapshotService.publish(
                Snapshot.BusinessType.VISIT, "SN-INIT-VISIT 初始就诊事件快照", "seed");
        seedVisitRecords();
        var dataset = visitDatasetService.compile(visitSnap.id, "初始就诊数据集（v1）", "seed");
        log.info("样例数据播种完成：17 个来源身份、4 份初始快照、6 条下游业务记录、"
                + "{} 条唯一就诊记录（29 次上报，其中 2 次为同流水重复回传），"
                + "就诊数据集版本 v{}（含五类验收样例）", 27, dataset.datasetVersion);
    }

    /**
     * 跨系统就诊记录样例（HIS 平台跨院区采集，业务地点/服务类型/关联凭据齐全）：
     * <ol>
     *   <li>孙七：跨院转诊 —— 院区A 门诊（挂号/签到/检验/处方）+ 转诊单 + 院区B 接收，合并为一个诊疗过程；</li>
     *   <li>周八：同日独立急诊 —— 上午门诊 + 夜间急诊各自独立凭据，计为两次就诊；</li>
     *   <li>吴九：日期级时间 —— 只有日期，进入待定；</li>
     *   <li>郑十：晚到转诊单 —— 转诊单 7 天后到达，路径进入待定；</li>
     *   <li>何十一：重复检验回传 + 跨院复查 —— 同内容重复回传被去重，复查间隔不受影响；</li>
     *   <li>钱十二：无凭据时间窗 —— 只能形成"可能事件"；</li>
     *   <li>未知身份：REG_C/P-C-999 未在快照中，无法归并。</li>
     * </ol>
     */
    private void seedVisitRecords() {
        // 孙七：跨院转诊
        record("HIS", "V-001-R1", "V-001", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-02T09:00:00Z", null, "ENC-A-101", null, null, null, null);
        record("HIS", "V-001-R2", "V-001", "CHECK_IN", "OUTPATIENT", "院区A",
                "2026-11-02T09:12:00Z", null, "ENC-A-101", null, null, null, null);
        record("HIS", "V-001-R3", "V-001", "LAB", "LAB", "采血中心A",
                "2026-11-02T09:40:00Z", null, "ENC-A-101", null, null, "血常规", null);
        record("HIS", "V-001-R4", "V-001", "PRESCRIPTION", "PHARMACY", "院区A",
                "2026-11-02T10:05:00Z", null, "ENC-A-101", null, null, "阿莫西林", null);
        record("HIS", "V-001-R5", "V-001", "REFERRAL", "REFERRAL", "院区A",
                "2026-11-02T10:20:00Z", null, "ENC-A-101", "REF-101", "院区B", null, null);
        record("HIS", "V-001-R6", "V-001", "REGISTRATION", "OUTPATIENT", "院区B",
                "2026-11-02T14:00:00Z", null, "ENC-B-101", "REF-101", "院区B", null, null);
        record("HIS", "V-001-R7", "V-001", "LAB", "LAB", "采血中心B",
                "2026-11-02T14:30:00Z", null, "ENC-B-101", "REF-101", "院区B", "尿常规", null);

        // 周八：同日门诊 + 夜间独立急诊
        record("HIS", "V-002-R1", "V-002", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-03T09:00:00Z", null, "ENC-A-102", null, null, null, null);
        record("HIS", "V-002-R2", "V-002", "CHECK_IN", "OUTPATIENT", "院区A",
                "2026-11-03T09:20:00Z", null, "ENC-A-102", null, null, null, null);
        record("HIS", "V-002-R3", "V-002", "LAB", "LAB", "采血中心A",
                "2026-11-03T09:50:00Z", null, "ENC-A-102", null, null, "血常规", null);
        record("HIS", "V-002-R4", "V-002", "REGISTRATION", "EMERGENCY", "院区A",
                "2026-11-03T20:10:00Z", null, "ENC-A-103", null, null, null, null);
        record("HIS", "V-002-R5", "V-002", "LAB", "LAB", "采血中心A",
                "2026-11-03T20:25:00Z", null, "ENC-A-103", null, null, "心肌酶", null);

        // 吴九：只有日期级时间
        record("HIS", "V-003-R1", "V-003", "REGISTRATION", "OUTPATIENT", "院区A",
                null, "2026-11-04", null, null, null, null, null);
        record("HIS", "V-003-R2", "V-003", "LAB", "LAB", "采血中心A",
                null, "2026-11-04", null, null, null, "糖化血红蛋白", null);

        // 郑十：晚到转诊单（开出 7 天后才到达）
        record("HIS", "V-004-R1", "V-004", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-10-20T09:00:00Z", null, "ENC-A-104", null, null, null, "2026-10-20T09:00:00Z");
        record("HIS", "V-004-R2", "V-004", "REFERRAL", "REFERRAL", "院区A",
                "2026-10-20T10:00:00Z", null, "ENC-A-104", "REF-104", "院区B", null, "2026-10-27T08:00:00Z");
        record("HIS", "V-004-R3", "V-004", "REGISTRATION", "OUTPATIENT", "院区B",
                "2026-10-21T14:00:00Z", null, "ENC-B-104", "REF-104", "院区B", null, "2026-10-21T14:00:00Z");

        // 何十一：重复检验回传（同源、同项目、2 分钟内）+ 跨院复查
        record("HIS", "V-005-R1", "V-005", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-05T09:00:00Z", null, "ENC-A-105", null, null, null, null);
        record("HIS", "V-005-R2", "V-005", "LAB", "LAB", "采血中心A",
                "2026-11-05T09:30:00Z", null, "ENC-A-105", null, null, "血常规", null);
        record("HIS", "V-005-R3", "V-005", "LAB", "LAB", "采血中心A",
                "2026-11-05T09:32:00Z", null, "ENC-A-105", null, null, "血常规", null);
        record("HIS", "V-005-R4", "V-005", "REGISTRATION", "OUTPATIENT", "院区B",
                "2026-11-12T10:00:00Z", null, "ENC-B-105", null, null, null, null);
        record("HIS", "V-005-R5", "V-005", "LAB", "LAB", "采血中心B",
                "2026-11-12T10:20:00Z", null, "ENC-B-105", null, null, "血常规", null);
        // 同一来源流水号、同内容重复回传：不新增记录行，只累计 reuploadCount
        record("HIS", "V-005-R2", "V-005", "LAB", "LAB", "采血中心A",
                "2026-11-05T09:30:00Z", null, "ENC-A-105", null, null, "血常规", null);

        // 钱十二：无就诊流水凭据，只能靠时间窗聚成"可能事件"
        record("HIS", "V-006-R1", "V-006", "IMAGING", "IMAGING", "影像中心",
                "2026-11-06T10:00:00Z", null, null, null, null, "胸部CT", null);
        record("HIS", "V-006-R2", "V-006", "PRESCRIPTION", "PHARMACY", "院区B",
                "2026-11-06T10:40:00Z", null, null, null, null, "头孢克肟", null);

        // 冯十三：同日跨院就诊但缺转诊凭据 → 后一次就诊待定（可能重复记录，也可能独立返院）
        record("HIS", "V-007-R1", "V-007", "REGISTRATION", "OUTPATIENT", "院区A",
                "2026-11-08T09:00:00Z", null, "ENC-A-107", null, null, null, null);
        record("HIS", "V-007-R2", "V-007", "REGISTRATION", "OUTPATIENT", "院区B",
                "2026-11-08T11:00:00Z", null, "ENC-B-107", null, null, null, null);

        // 身份尚未确定：REG_C 未接入主索引，也不在快照中
        record("REG_C", "C-001", "P-C-999", "REGISTRATION", "OUTPATIENT", "院区C",
                "2026-11-07T15:00:00Z", null, "ENC-C-999", null, null, null, null);
    }

    private void record(String sourceSystem, String sourceRecordId, String sourcePatientId,
                        String eventType, String serviceType, String campusCode,
                        String occurredAt, String occurredDate, String encounterKey,
                        String referralKey, String referralTargetCampus,
                        String serviceDetail, String receivedAt) {
        visitRecordService.ingest(new VisitRecordService.IngestCommand(
                sourceSystem, sourceRecordId, sourcePatientId, eventType, serviceType,
                campusCode, occurredAt, occurredDate, encounterKey, referralKey,
                referralTargetCampus, serviceDetail,
                receivedAt != null ? receivedAt : occurredAt));
    }
}

