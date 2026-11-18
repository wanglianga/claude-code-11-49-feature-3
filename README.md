# 就诊人主索引（MPI）合并拆分、快照稳定与跨院就诊路径服务

面向连锁诊所的就诊人主索引服务。不同院区的挂号、检验、结算系统各自生成就诊人编号，
本服务负责：**来源身份接入 →候选匹配 → 人工决议（合并/拆分）→ 时态关系图 → 业务快照 → 影响清单**，
保证已确认的结算、检验报告和经营报表"当时引用了谁"永远可复现。

在此基础上提供**跨系统就诊事件归并与跨院路径数据集**：把挂号、签到、检验、处方和转诊记录
按凭据 / 转诊关系 / 服务链归并为可审计的就诊事件，生成跨院路径、各中心复诊率与检验复查间隔，
并明确区分**确定路径、可能重复、无法归并**三类口径。

## 核心设计

| 决策 | 实现 |
| --- | --- |
| 身份历史模型 | **时态关系图**：`mpi_link`（身份→主体）带生效图谱版本区间 `[validFrom, validTo)`，按业务时点解析身份 |
| 快照稳定 | 预约/结算/检验/分析/就诊数据集启动时**发布快照固定图谱版本**，并把该版本的归属映射物化到 `snapshot_mapping`，读取快、可复核、永不回写 |
| 审核隔离 | 候选对、候选快照都是"尚在审核的身份推断"，下游只能读 `PUBLISHED` 快照（候选快照读取返回 409） |
| 并发决议 | 决议携带 `expectedGraphVersion`，事务内对全局图谱行加写锁：**先提交者锁定该对及相邻关系，后提交者收 409 必须重新检查**，不会形成一人并入两个互斥主体 |
| 身份更正 | 来源证件更正只更新证据字段并重跑匹配；**已发布快照不回写**，影响经新决议→影响分析→**候选快照**供业务确认 |
| 关账保护 | 已关账结算凭证**绝不改写**，身份拆分只生成 `ADJUSTMENT_LIST` 调整清单供财务线下处理；检验报告、预约提醒按各自业务规则领取新快照 |

### 就诊事件归并规则（本迭代新增）

| 规则 | 实现与约束 |
| --- | --- |
| 归并优先级 | **就诊流水凭据（encounterKey）> 转诊关系（referralKey）> 服务类型 + 时间窗**；凭据明确时不依赖时间窗 |
| 时间窗聚类 | 同院、同服务轨道、同日、相邻间隔 ≤ 120 分钟的**无凭据**记录聚为事件；急诊单独成轨（`EMERGENCY`），**同日独立急诊不会被并入门诊** |
| 服务链 | 挂号 / 签到 / 采血 / 影像记录若携带同一就诊流水，全部并入该次就诊，不单独形成复诊；采血与影像中心计入过程覆盖院区 |
| 复诊口径 | 复诊以**诊疗过程（episode）**计数：跨院转诊（有转诊凭据）连接的两次就诊合为 1 个过程，因此转院不会被记成两次复诊，记录粒度也不会放大复诊率 |
| 不反推 | 来源记录只保存来源时间、业务地点、服务类型、关联凭据与项目明细，**不接收诊断 / 结算结果**，事件归并不依据后续结果 |
| 日期级时间 | 只有日期无具体时刻的记录**不进入时间窗**，按"主体 + 业务日期"归入 `AMBIGUOUS_TIME` 待定类别 |
| 重复回传 | 同一来源流水号同内容重复上报：不新增记录行，累计 `reuploadCount` 并计入质量审查；不同流水号但同源同业务指纹且时间相差 ≤10 分钟 → `POSSIBLE_DUPLICATE`，保留原记录并从计数剔除 |
| 晚到转诊单 | 到达时间晚于开出时间超过阈值（1 天）→ 路径 `LATE_PENDING`、目标侧就诊 `PENDING_EVENT`；人工核验后**新版本**转为确定转诊 |
| 身份未确定 | 来源身份不在所引用快照中 → `UNRESOLVED_IDENTITY`，单独报告，不参与事件归并 |
| 五阶段状态 | 身份快照引用 / 来源去重 / 事件编译 / 路径统计 / 质量审查各自持有阶段状态（`PENDING/RUNNING/COMPLETED/PARTIAL/FAILED`） |
| 版本化 | 数据集版本号单调递增并串链 `previousDatasetId`；快照修订或补传记录后再次编译**只生成新版本**，旧版本内容永不回写；输入未变化时编译幂等 |

技术栈：Java 17+（本地用 JDK 25 验证）、Spring Boot 3.5、Spring Data JPA、PostgreSQL、springdoc-openapi（Swagger UI）。

## 一键启动（Docker Compose）

```bash
docker compose up --build
```

启动后：应用 http://localhost:8080 ，Swagger UI http://localhost:8080/swagger-ui.html 。
Compose 包含 PostgreSQL 18 容器，首次启动自动建表并播种样例数据。
重置数据：`docker compose down -v && docker compose up --build`。

## 本地开发

前置：JDK 17+（本仓库在 JDK 25 下 验过）、Maven 3.9+、PostgreSQL（默认连接 `127.0.0.1:5432`，库/用户/密码 `ccdev`，可用环境变量覆盖）。

```bash
# 启动（自动建表 + 播种样例数据）
mvn spring-boot:run

# 自定义数据库连接
DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=ccdev DB_USER=ccdev DB_PASS=ccdev mvn spring-boot:run
```

## 测试账号（操作员）

接口通过请求头 `X-Operator-Id` 标识操作员（缺省 `system`）：

| 账号 | 用途 |
| --- | --- |
| `op_zhang` | 主索引操作员 1（合并/拆分/确认快照/核验晚到转诊单） |
| `op_li` | 主索引操作员 2（并发决议回放中的后提交者） |

## 验证方式

### 1. 集成测试（19 个用例，真实 PostgreSQL）

```bash
# 准备测试库（首次）
psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE ccdev_test;"
# 运行（使用独立库 ccdev_test，create-drop，不影响开发数据）
mvn test
```

覆盖身份与决议（9 个）：接入与评分、合并版本递增与时点解析、过期版本 409 后重检成功、
**双线程并发决议恰好一个成功**、误并拆分恢复、证件更正不回写快照、关账只出调整清单、
候选快照确认按业务规则生效、家庭共用号码拒绝合并。

覆盖就诊事件归并（10 个）：同日独立急诊不被并入门诊、跨院转诊合并为一个诊疗过程、
重复检验回传被去重且复查间隔只计一次、日期级时间进入待定、晚到转诊单先待定后核验、
身份未确定单独报告、记录粒度不放大复诊率、编译幂等与补传生成新版本、非 VISIT 快照被拒绝、无凭据只形成可能事件。

### 2. 验收回放脚本（78 项断言，需服务已启动且为全新播种数据）

```bash
./scripts/verify.sh            # 默认 BASE_URL=http://127.0.0.1:8080
```

回放身份决议六场景：多院重复挂号合并 → **误并拆分 + 关账后调整** → **来源证件更正** → **并发决议** →
按版本复现关系链 → **就诊事件归并（五类样例）**。全部通过时退出码为 0。

就诊事件部分逐项验证：跨院转诊合并为 1 个过程、同日独立急诊为 2 个独立过程、重复检验回传去重且
复查间隔不重复、日期级时间不产生确定复诊、晚到转诊单进入待定、无凭据/未确定身份正确分类、
记录粒度不影响复诊、运营报告三类口径齐全、编译幂等、补传生成新版本且旧版本不回写、快照修订引用新快照且旧版本不回写、
晚到转诊核验后新版本转为确定且不增复诊次数。

### 3. 手工回放（curl 示例）

```bash
# 跨系统就诊记录接入（同一流水号重复上报会累计 reuploadCount）
curl -s -X POST http://127.0.0.1:8080/api/visit-records \
  -H 'Content-Type: application/json' \
  -d '{"sourceSystem":"HIS","sourceRecordId":"V-001-R1","sourcePatientId":"V-001",
       "eventType":"REGISTRATION","serviceType":"OUTPATIENT","campusCode":"院区A",
       "occurredAt":"2026-11-02T09:00:00Z","encounterKey":"ENC-A-101"}' | jq

# 编译就诊事件数据集（为空则用最新 VISIT 快照；输入未变时幂等返回现有版本）
curl -s -X POST http://127.0.0.1:8080/api/visit-datasets \
  -H 'Content-Type: application/json' -H 'X-Operator-Id: op_zhang' \
  -d '{"label":"运营月度数据集"}' | jq

# 运营报告：确定路径 / 可能重复 / 无法归并 / 待定 + 各中心复诊率
curl -s http://127.0.0.1:8080/api/visit-datasets/latest | jq -r .id > /tmp/ds
curl -s "http://127.0.0.1:8080/api/visit-datasets/$(cat /tmp/ds)/report" | jq

# 事件审计（成员记录为何归入该事件）与跨院路径
curl -s "http://127.0.0.1:8080/api/visit-datasets/$(cat /tmp/ds)/events" | jq
curl -s "http://127.0.0.1:8080/api/visit-datasets/$(cat /tmp/ds)/pathways" | jq
curl -s "http://127.0.0.1:8080/api/visit-events/<事件ID>/members" | jq

# 晚到转诊单人工核验 → 再次编译生成新版本
curl -s -X POST "http://127.0.0.1:8080/api/visit-records/<转诊记录ID>/verify-referral" \
  -H 'Content-Type: application/json' -H 'X-Operator-Id: op_zhang' -d '{"note":"已核对纸质转诊单"}' | jq
curl -s -X POST http://127.0.0.1:8080/api/visit-datasets -H 'Content-Type: application/json' -d '{}' | jq -r .datasetVersion

# 身份侧示例（历史行为不变）
curl -s 'http://127.0.0.1:8080/api/candidates?status=PENDING' | jq
V=$(curl -s http://127.0.0.1:8080/api/graph/version | jq -r .graphVersion)
curl -s -X POST http://127.0.0.1:8080/api/resolutions/merge \
  -H 'Content-Type: application/json' -H 'X-Operator-Id: op_zhang' \
  -d "{\"candidateId\":\"<候选对ID>\",\"expectedGraphVersion\":$V,\"reason\":\"两院重复挂号\"}" | jq
curl -s 'http://127.0.0.1:8080/api/resolve?sourceSystem=LAB_A&sourcePatientId=L-A-001&atVersion=0' | jq
```

### 重置样例数据（本地）

```bash
psql -h 127.0.0.1 -U ccdev -d ccdev -c "drop schema public cascade; create schema public;"
# 重启应用后会自动重新播种
```

## 样例数据（启动自动播种）

身份决议样例：

| 场景 | 数据 | 预期处理 |
| --- | --- | --- |
| 多院重复挂号 | 张三 `REG_A/P-A-001` × `REG_B/P-B-001`（证件号一致，评分 1.0） | 合并 |
| 误并素材 | 两个同名陈晨 `REG_A/P-A-002` × `REG_B/P-B-002`（仅姓名相同，低于候选阈值） | 人工误并 → 拆分回放 |
| 家庭共用号码 | 李四 × 李四中（共用手机号，评分 0.4） | 人工拒绝合并 |
| 证件更正 | 赵六 `LAB_A/L-A-001` 证件号末位录错（`...110` 应为 `...111`） | 更正 → 新候选 → 合并 |

下游记录：陈晨B 预约（BOOKED）、李四中预约（BOOKED）、陈晨A 结算单（**已关账**，2026-10，¥880）、
赵六结算单（未关账，2026-11，¥1200）、陈晨A 血常规（未签发）、赵六 尿常规（未签发）。

### 就诊事件归并样例（HIS 跨院数据平台，27 条唯一来源记录 / 29 次上报，初始数据集 v1）

| 场景 | 样例主体 | 记录与凭据 | 期望结果 |
| --- | --- | --- | --- |
| 跨院转诊 | 孙七 `V-001` | 院区A 挂号/签到/采血/处方 + 转诊单 `REF-101` + 院区B 挂号/采血 | 2 个确定事件、1 个跨院诊疗过程、`CROSS_CAMPUS_REFERRAL` 确定路径 |
| 同日独立急诊 | 周八 `V-002` | 上午门诊（挂号/签到/采血）+ 夜间急诊（挂号/采血），各自独立凭据 | 2 个确定事件、2 个独立过程，**不因同日被合并** |
| 重复检验回传 | 何十一 `V-005` | 09:30 与 09:32 同源同项目血常规；一周后院区B 复查 | 1 条记录判为 `POSSIBLE_DUPLICATE`；复查间隔只计 1 条（约 168.8 小时，跨院） |
| 日期级时间 | 吴九 `V-003` | 挂号与检验仅有日期 `2026-11-04` | 两条记录 `AMBIGUOUS_TIME`，**不产生确定复诊** |
| 晚到转诊单 | 郑十 `V-004` | 转诊单开出 7 天后才到达 | 路径 `LATE_PENDING`，目标侧记录 `PENDING_EVENT`；核验后新版本转确定 |
| 无凭据跨院 | 冯十三 `V-007` | 同日两院区挂号，无转诊单 | 后一次 `PENDING_EVENT` + `UNVERIFIED_TRANSFER` 待定 |
| 无凭据时间窗 | 钱十二 `V-006` | 影像 + 处方，无就诊流水 | 两条 `PROBABLE_EVENT`（可能事件，不计确定复诊） |
| 身份未确定 | `REG_C/P-C-999` | 未接入主索引、不在快照中 | `UNRESOLVED_IDENTITY`，身份引用阶段标记 `PARTIAL` |

初始数据集（v1）实测计数：27 条记录（播种上报 29 条，其中 2 条为同流水同内容重复回传，合并进原记录行）、
8 个确定事件、7 个确定诊疗过程（含 1 个跨院过程）、3 条确定路径（含 `CROSS_CAMPUS_REFERRAL` 1 条与
`SAME_CAMPUS_RETURN` 1 条）、1 条晚到待定路径、1 条跨院缺凭据待定路径、2 个待定事件（2 条待定记录）、
可能重复 1 条、无法归并 3 条（身份未定 1 + 日期级 2）、检验复查间隔 1 条、质量审查条目 9 条。

## API 概览（完整见 Swagger UI）

| 分组 | 接口 |
| --- | --- |
| 来源身份 | `POST/GET /api/source-identities`、`GET /api/source-identities/{id}`、`POST /api/source-identities/{id}/corrections` |
| 候选匹配 | `GET /api/candidates`、`GET /api/candidates/{id}`、`POST /api/candidates/{id}/reject` |
| 人工决议 | `POST /api/resolutions/merge`、`POST /api/resolutions/split`、`GET /api/resolutions[/{id}]` |
| 时态关系图 | `GET /api/graph/version`、`GET /api/graph/masters[/{id}]`、`GET /api/resolve?atVersion=` |
| 业务快照 | `POST /api/snapshots`、`GET /api/snapshots[/{id}]`、`GET /{id}/mappings`、`GET /{id}/resolve`、`POST /{id}/confirm`、`POST /{id}/reject` |
| 影响清单 | `GET /api/impacts`、`POST /api/impacts/{id}/ack` |
| 下游模拟 | `POST/GET /api/biz/appointments`、`POST /{id}/remind`、`POST/GET /api/biz/settlements`、`POST /{id}/close`、`POST/GET /api/biz/lab-reports`、`POST /{id}/finalize` |
| 就诊来源记录 | `POST/GET /api/visit-records`、`GET /api/visit-records/{id}`、`POST /api/visit-records/{id}/verify-referral` |
| 就诊数据集 | `POST/GET /api/visit-datasets`、`GET /latest`、`GET /{id}`、`GET /{id}/records`、`/events`、`/pathways`、`/episodes`、`/lab-intervals`、`/center-stats`、`/quality`、`/report` |
| 事件审计 | `GET /api/visit-events/{eventId}/members` |
| 质量审查 | `POST /api/visit-quality/{findingId}/ack`（ack / dismiss） |

> 数据集编译需引用 `VISIT` 类型已发布快照；启动播种已发布 `SN-INIT-VISIT`，也可通过
> `POST /api/snapshots`（`businessType=VISIT`）在身份决议后发布新快照，再编译新版本。

## 目录结构

```
src/main/java/com/clinic/mpi/
├── domain/      # 实体：来源身份/候选对/决议/图谱/时态链接/快照/影响项/下游记录
│                #      + 就诊记录、数据集、数据集记录、事件、事件成员、路径、诊疗过程、复查间隔、中心统计、质量审查
├── repo/        # Spring Data JPA 仓库（含悲观写锁查询）
├── service/     # Match/Resolution/Snapshot/Impact/Biz/Identity/Graph 服务
│                #      + VisitRecordService（记录接入与去重）、VisitDatasetService（归并/路径/统计/质量）
├── web/         # 8 个 REST 控制器 + 全局异常处理（409 版本冲突）
└── seed/        # 样例数据播种（幂等，含五类就诊归并样例并自动编译 v1）
scripts/verify.sh  # 验收回放脚本（78 项断言）
```

## 验证结论（实际执行记录）

以下验证均在本仓库交付前实际执行通过：

- `mvn test`：19 个集成测试全部通过（`MpiFlowTest` 9 个 + `VisitDatasetFlowTest` 10 个，真实 PostgreSQL 18，独立测试库 `ccdev_test`）。
- `./scripts/verify.sh`：78 项验收断言全部通过（本地启动 `mvn spring-boot:run` + 全新播种数据）。
- Swagger UI（`/swagger-ui.html`）与 OpenAPI 文档（`/v3/api-docs`，44 个路径，其中就诊事件与跨院路径 16 个）已实际访问确认可用。
