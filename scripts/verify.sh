#!/usr/bin/env bash
# 验收回放脚本：多院重复挂号合并、误并拆分、并发决议、来源证件更正、关账后调整
# 前置条件：服务已启动且为全新播种数据（图谱版本 = 0）。重置方式见 README。
set -euo pipefail

BASE=${BASE_URL:-http://127.0.0.1:8080}
OP1=${OP1:-op_zhang}
OP2=${OP2:-op_li}

pass=0; fail=0
ok()   { pass=$((pass+1)); echo "  ✔ $1"; }
bad()  { fail=$((fail+1)); echo "  ✘ $1"; }
check(){ # check <描述> <实际> <期望>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1（期望=$3 实际=$2）"; fi
}
check_ne(){ # check_ne <描述> <实际> <不应等于>
  if [ "$2" != "$3" ]; then ok "$1"; else bad "$1（不应等于 $3）"; fi
}

get(){ curl -s "$BASE$1"; }
post(){ # post <path> <json> <operator>
  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "X-Operator-Id: ${3:-system}" -d "${2:-{}}"
}
post_status(){ # post_status <path> <json> <operator> -> http code, body in /tmp/mpi-verify-body.json
  curl -s -o /tmp/mpi-verify-body.json -w "%{http_code}" -X POST "$BASE$1" \
       -H 'Content-Type: application/json' -H "X-Operator-Id: ${3:-system}" -d "${2:-{}}"
}
get_status(){ # get_status <path> -> http code, body in /tmp/mpi-verify-body.json
  curl -s -o /tmp/mpi-verify-body.json -w "%{http_code}" "$BASE$1"
}

version(){ get /api/graph/version | jq -r '.graphVersion'; }
identity_id(){ get /api/source-identities | jq -r --arg s "$1" --arg p "$2" \
  '.[] | select(.sourceSystem==$s and .sourcePatientId==$p) | .id'; }
find_candidate(){ get "/api/candidates?status=PENDING" | jq -r --arg a "$1" --arg b "$2" \
  '[.[] | select((.identityAId==$a and .identityBId==$b) or (.identityAId==$b and .identityBId==$a))][0].id // empty'; }
candidate_status(){ get "/api/candidates/$1" | jq -r '.status'; }
resolve_master(){ get "/api/resolve?sourceSystem=$1&sourcePatientId=$2" | jq -r '.masterId'; }
snap_resolve(){ get "/api/snapshots/$1/resolve?sourceSystem=$2&sourcePatientId=$3" | jq -r '.masterId'; }
latest_snapshot(){ get /api/snapshots | jq -r --arg t "$1" --arg s "$2" \
  '[.[] | select(.businessType==$t and .status==$s)][0].id // empty'; }
appt_field(){ get /api/biz/appointments | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
settle_field(){ get /api/biz/settlements | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
lab_field(){ get /api/biz/lab-reports | jq -r --arg p "$1" \
  '[.[] | select(.sourcePatientId==$p)][0].'"$2"; }
impact_count(){ get "/api/impacts?resolutionId=$1" | jq --arg t "$2" --arg a "$3" \
  '[.[] | select(.businessType==$t and .action==$a)] | length'; }

echo "=========================================================="
echo " MPI 验收回放  BASE=$BASE  操作员: $OP1 / $OP2"
echo "=========================================================="

V0=$(version)
if [ "$V0" != "0" ]; then
  echo "!! 当前图谱版本为 $V0，验收脚本要求全新播种数据（版本 0）。"
  echo "!! 请先重置数据库并重启服务（见 README「重置样例数据」）。"
  exit 1
fi

A1=$(identity_id REG_A P-A-001); B1=$(identity_id REG_B P-B-001)
A2=$(identity_id REG_A P-A-002); B2=$(identity_id REG_B P-B-002)
A3=$(identity_id REG_A P-A-003); B3=$(identity_id REG_B P-B-003)
A4=$(identity_id REG_A P-A-004); L1=$(identity_id LAB_A L-A-001)
S1=$(identity_id SETTLE_A S-A-001)
SN_APPT=$(latest_snapshot APPOINTMENT PUBLISHED)
SN_SETTLE=$(latest_snapshot SETTLEMENT PUBLISHED)
SN_LAB=$(latest_snapshot LAB PUBLISHED)
M_B2_ORIG=$(resolve_master REG_B P-B-002)
M_A2_ORIG=$(resolve_master REG_A P-A-002)
M_L1_ORIG=$(resolve_master LAB_A L-A-001)

echo
echo "【场景0】初始播种数据"
N_CAND=$(get "/api/candidates?status=PENDING" | jq 'length')
check "待处理候选对 5 个（含家庭共用号码 0.4 分对）" "$N_CAND" "5"
check "张三两院候选对评分 1.0" \
  "$(get "/api/candidates?status=PENDING" | jq -r --arg a "$A1" --arg b "$B1" '[.[] | select((.identityAId==$a and .identityBId==$b) or (.identityAId==$b and .identityBId==$a))][0].score')" "1"

echo
echo "【场景1】多院重复挂号：张三（REG_A/P-A-001 × REG_B/P-B-001）合并"
C_ZS=$(find_candidate "$A1" "$B1")
V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZS\",\"expectedGraphVersion\":$V,\"reason\":\"两院重复挂号，证件号一致\"}" "$OP1")
check "合并后图谱版本 +1" "$(echo "$R" | jq -r '.graphVersion')" "$((V+1))"
check "张三两来源解析到同一主体" "$(resolve_master REG_A P-A-001)" "$(resolve_master REG_B P-B-001)"
check "候选对状态变为 MERGED" "$(candidate_status "$C_ZS")" "MERGED"

echo
echo "【场景2】误并拆分 + 关账后调整（同名陈晨被人工误并）"
V=$(version)
R=$(post /api/resolutions/merge "{\"identityIds\":[\"$A2\",\"$B2\"],\"expectedGraphVersion\":$V,\"reason\":\"客服误并：同名同姓\"}" "$OP1")
M3=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID2=$(echo "$R" | jq -r '.id')
check "误并后两个陈晨同属新主体" "$(resolve_master REG_B P-B-002)" "$M3"
check "生成调整清单（已关账结算 ADJUSTMENT_LIST）" "$(impact_count "$RID2" SETTLEMENT ADJUSTMENT_LIST)" "1"
check "生成提醒切换影响（REMIND_SWITCH）" "$(impact_count "$RID2" APPOINTMENT REMIND_SWITCH)" "1"
check "生成检验归属影响（REATTRIBUTE）" "$(impact_count "$RID2" LAB REATTRIBUTE)" "1"
check "生成分析口径影响（INFO）" "$(impact_count "$RID2" ANALYTICS INFO)" "2"

CS_APPT=$(latest_snapshot APPOINTMENT CANDIDATE)
CODE=$(get_status "/api/snapshots/$CS_APPT/resolve?sourceSystem=REG_B&sourcePatientId=P-B-002")
check "候选快照未确认前下游不可读（409）" "$CODE" "409"

post "/api/snapshots/$CS_APPT/confirm" "{}" "$OP1" >/dev/null
check "确认预约候选快照后：预约归属切到合并主体" "$(appt_field P-B-002 masterId)" "$M3"
check "误并后果可见：提醒电话错用陈晨A号码" "$(appt_field P-B-002 contactPhone)" "13700000002"

CS_SETTLE=$(latest_snapshot SETTLEMENT CANDIDATE)
post "/api/snapshots/$CS_SETTLE/confirm" "{}" "$OP1" >/dev/null
check "已关账结算单不被改写（仍属原主体）" "$(settle_field P-A-002 masterId)" "$M_A2_ORIG"
check "已关账结算单状态仍 CLOSED" "$(settle_field P-A-002 status)" "CLOSED"
check "调整清单保持 PENDING 待财务人工处理" \
  "$(get "/api/impacts?resolutionId=$RID2" | jq -r --arg a "ADJUSTMENT_LIST" '[.[] | select(.action==$a)][0].status')" "PENDING"

CS_LAB=$(latest_snapshot LAB CANDIDATE)
post "/api/snapshots/$CS_LAB/confirm" "{}" "$OP1" >/dev/null
check "未签发检验报告随确认快照改归属" "$(lab_field P-A-002 masterId)" "$M3"

# 发现误并 → 拆分
V=$(version)
R=$(post /api/resolutions/split "{\"masterId\":\"$M3\",\"splitOutIdentityIds\":[\"$B2\"],\"expectedGraphVersion\":$V,\"reason\":\"复核发现误并，拆分陈晨B\"}" "$OP1")
M4=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID2S=$(echo "$R" | jq -r '.id')
check "拆分后陈晨B 进入新主体" "$(resolve_master REG_B P-B-002)" "$M4"
check "拆分后陈晨A 留在原合并主体" "$(resolve_master REG_A P-A-002)" "$M3"
check_ne "拆分后两陈晨不再同主体" "$(resolve_master REG_B P-B-002)" "$(resolve_master REG_A P-A-002)"
check "初始快照不回写：SN-INIT-APPT 仍解析到原单例主体" "$(snap_resolve "$SN_APPT" REG_B P-B-002)" "$M_B2_ORIG"
check "拆分生成新的提醒切换影响" "$(impact_count "$RID2S" APPOINTMENT REMIND_SWITCH)" "1"

CS_APPT2=$(latest_snapshot APPOINTMENT CANDIDATE)
post "/api/snapshots/$CS_APPT2/confirm" "{}" "$OP1" >/dev/null
check "确认后预约归属回到陈晨B 自己的主体" "$(appt_field P-B-002 masterId)" "$M4"
check "提醒电话恢复为陈晨B 本人号码" "$(appt_field P-B-002 contactPhone)" "13711110002"

ADJ_ID=$(get "/api/impacts?resolutionId=$RID2" | jq -r '[.[] | select(.action=="ADJUSTMENT_LIST")][0].id')
post "/api/impacts/$ADJ_ID/ack" "{\"note\":\"财务已线下调整 2026-10 账期\"}" "$OP1" >/dev/null
check "调整清单人工确认后状态 ACKED" \
  "$(get "/api/impacts?resolutionId=$RID2" | jq -r '[.[] | select(.action=="ADJUSTMENT_LIST")][0].status')" "ACKED"

echo
echo "【场景3】来源证件更正（赵六检验系统证件号末位录错）"
check "更正前 SN-INIT-LAB 中赵六检验身份为单例主体" "$(snap_resolve "$SN_LAB" LAB_A L-A-001)" "$M_L1_ORIG"
post "/api/source-identities/$L1/corrections" \
  "{\"field\":\"idCardNo\",\"newValue\":\"110101197703031111\",\"note\":\"检验系统更正证件号末位\"}" "$OP1" >/dev/null
check "旧证据候选对已作废（SUPERSEDED）" \
  "$(get /api/candidates | jq '[.[] | select(.status=="SUPERSEDED")] | length')" "2"
C_ZL=$(find_candidate "$A4" "$L1")
check "更正后生成 1.0 分新候选对" \
  "$(get "/api/candidates/$C_ZL" | jq -r '.score')" "1"
check "已发布快照不回写：SN-INIT-LAB 仍解析到原主体" "$(snap_resolve "$SN_LAB" LAB_A L-A-001)" "$M_L1_ORIG"

V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZL\",\"expectedGraphVersion\":$V,\"reason\":\"证件更正后确认同一就诊人\"}" "$OP1")
M5=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
RID3=$(echo "$R" | jq -r '.id')
check "赵六挂号与检验身份合并" "$(resolve_master LAB_A L-A-001)" "$M5"
check "影响分析生成检验候选快照" "$(impact_count "$RID3" LAB REATTRIBUTE)" "1"
CS_LAB3=$(latest_snapshot LAB CANDIDATE)
post "/api/snapshots/$CS_LAB3/confirm" "{}" "$OP1" >/dev/null
check "检验报告（尿常规）归属更新到合并主体" "$(lab_field L-A-001 masterId)" "$M5"

echo
echo "【场景4】并发决议：先提交者锁定，后提交者必须重新检查"
C_ZS1=$(find_candidate "$A4" "$S1")
C_FAM=$(find_candidate "$A3" "$B3")
V=$(version)
R=$(post /api/resolutions/merge "{\"candidateId\":\"$C_ZS1\",\"expectedGraphVersion\":$V,\"reason\":\"结算系统赵六并入\"}" "$OP1")
M6=$(echo "$R" | jq -r '.detail' | jq -r '.newMaster')
check "操作员1 合并赵六结算身份成功（版本 $((V+1))）" "$(echo "$R" | jq -r '.graphVersion')" "$((V+1))"

CODE=$(post_status /api/resolutions/merge "{\"candidateId\":\"$C_FAM\",\"expectedGraphVersion\":$V,\"reason\":\"操作员2 基于过期版本提交\"}" "$OP2")
check "操作员2 持过期版本提交被拒绝（409）" "$CODE" "409"
check "409 响应携带当前图谱版本" "$(jq -r '.currentGraphVersion' /tmp/mpi-verify-body.json)" "$((V+1))"

CODE=$(post_status /api/resolutions/merge "{\"candidateId\":\"$C_ZS1\",\"expectedGraphVersion\":$V,\"reason\":\"操作员2 重复处理同一候选对\"}" "$OP2")
check "同一候选对被重复提交同样 409" "$CODE" "409"

V=$(version)
R=$(post "/api/candidates/$C_FAM/reject" "{\"reason\":\"家庭共用号码，非同一就诊人\"}" "$OP2")
check "操作员2 重新检查后拒绝家庭共用号码候选" "$(echo "$R" | jq -r '.status')" "REJECTED"
check_ne "李四与李四中仍是不同主体" "$(resolve_master REG_A P-A-003)" "$(resolve_master REG_B P-B-003)"
check "李四中预约电话未被切换" "$(appt_field P-B-003 contactPhone)" "13900000003"

CS_SETTLE4=$(latest_snapshot SETTLEMENT CANDIDATE)
post "/api/snapshots/$CS_SETTLE4/confirm" "{}" "$OP1" >/dev/null
check "未关账结算单随确认快照更新归属" "$(settle_field S-A-001 masterId)" "$M6"
CS_LAB4=$(latest_snapshot LAB CANDIDATE)
[ -n "$CS_LAB4" ] && post "/api/snapshots/$CS_LAB4/confirm" "{}" "$OP1" >/dev/null || true
check "检验报告归属最终主体" "$(lab_field L-A-001 masterId)" "$M6"

echo
echo "【场景5】历史可复现：按版本解析关系链"
check "v0 时赵六检验身份为单例主体" "$(get "/api/resolve?sourceSystem=LAB_A&sourcePatientId=L-A-001&atVersion=0" | jq -r '.masterId')" "$M_L1_ORIG"
check "当前版本赵六三身份同主体" "$(resolve_master SETTLE_A S-A-001)" "$M6"
check "决议链完整（4 合并 + 1 拆分）" "$(get /api/resolutions | jq '[.[] | select(.type=="MERGE")] | length')" "4"
check "拆分决议数" "$(get /api/resolutions | jq '[.[] | select(.type=="SPLIT")] | length')" "1"

echo
echo "【场景6】就诊事件归并与跨院路径数据集（五类验收样例）"
DS_ID=$(get "/api/visit-datasets/latest" | jq -r '.id')
DS_VER=$(get "/api/visit-datasets/latest" | jq -r '.datasetVersion')
DS0_SNAPSHOT=$(get "/api/visit-datasets/$DS_ID" | jq -r '.snapshotId')
REPORT() { get "/api/visit-datasets/$1/report"; }
ev_of_v() { # ev_of_v <sourcePatientId> -> events JSON of that master
  local mid
  mid=$(get "/api/visit-datasets/$DS_ID/records" | jq -r --arg p "$1" \
    '[.[] | select(.sourcePatientId==$p)][0].masterId')
  get "/api/visit-datasets/$DS_ID/events?masterId=$mid"
}
check "数据集已引用 VISIT 快照且五阶段均完成/部分完成" \
  "$(get "/api/visit-datasets/$DS_ID" | jq -r '[.identityStatus,.dedupStatus,.compileStatus,.pathStatus,.qualityStatus] | map(select(.=="COMPLETED" or .=="PARTIAL")) | length')" "5"

# 6.1 跨院转诊：A 门诊→转诊→B 接收 属同一诊疗过程（不拆成两次复诊）
check "孙七（跨院转诊）产生 2 个确定事件" "$(ev_of_v V-001 | jq '[.[] | select(.status=="CONFIRMED")] | length')" "2"
check "孙七两事件同属 1 个诊疗过程" "$(ev_of_v V-001 | jq '[.[] | .episodeKey] | unique | length')" "1"
check "孙七两事件由已确认跨院转诊连接" \
  "$(get "/api/visit-datasets/$DS_ID/pathways?masterId=$(get "/api/visit-datasets/$DS_ID/records" | jq -r '[.[] | select(.sourcePatientId=="V-001")][0].masterId')" | jq '[.[] | select(.type=="CROSS_CAMPUS_REFERRAL" and .status=="CONFIRMED" and .referralKey=="REF-101")] | length')" "1"

# 6.2 同日独立急诊：门诊与夜间急诊是两次独立就诊
check "周八（同日独立急诊）确定事件数 2" "$(ev_of_v V-002 | jq '[.[] | select(.status=="CONFIRMED")] | length')" "2"
check "周八门诊与急诊分属不同诊疗过程" "$(ev_of_v V-002 | jq '[.[] | .episodeKey] | unique | length')" "2"
check "存在同院独立返院路径" \
  "$(get "/api/visit-datasets/$DS_ID/pathways?masterId=$(get "/api/visit-datasets/$DS_ID/records" | jq -r '[.[] | select(.sourcePatientId=="V-002")][0].masterId')" | jq '[.[] | select(.type=="SAME_CAMPUS_RETURN" and .status=="CONFIRMED")] | length')" "1"

# 6.3 重复检验回传：同指纹记录进入可能重复，复查间隔仍只有 1 条
check "何十一重复检验回传记录归入可能重复" \
  "$(get "/api/visit-datasets/$DS_ID/records" | jq '[.[] | select(.sourcePatientId=="V-005" and .classification=="POSSIBLE_DUPLICATE")] | length')" "1"
check "何十一跨院复查间隔只计 1 条（重复回传未重复计数）" \
  "$(get "/api/visit-datasets/$DS_ID/lab-intervals" | jq --arg m "$(get "/api/visit-datasets/$DS_ID/records" | jq -r '[.[] | select(.sourcePatientId=="V-005")][0].masterId')" '[.[] | select(.masterId==$m and .testName=="血常规")] | length')" "1"
check "复查间隔为跨院（采血中心A→采血中心B）" \
  "$(get "/api/visit-datasets/$DS_ID/lab-intervals" | jq -r --arg m "$(get "/api/visit-datasets/$DS_ID/records" | jq -r '[.[] | select(.sourcePatientId=="V-005")][0].masterId')" '[.[] | select(.masterId==$m)][0].crossCampus')" "true"

# 6.4 日期级时间：进入待定，不产生确定复诊
check "吴九（日期级时间）记录归入 AMBIGUOUS_TIME" \
  "$(get "/api/visit-datasets/$DS_ID/records" | jq '[.[] | select(.sourcePatientId=="V-003" and .classification=="AMBIGUOUS_TIME_EVENT")] | length')" "2"
check "吴九无确定诊疗过程（不强行增加复诊）" \
  "$(get "/api/visit-datasets/$DS_ID/episodes?masterId=$(get "/api/visit-datasets/$DS_ID/records" | jq -r '[.[] | select(.sourcePatientId=="V-003")][0].masterId')" | jq 'length')" "0"

# 6.5 晚到转诊单：先待定，不并入确定路径
check "郑十晚到转诊单产生 LATE_PENDING 路径" \
  "$(get "/api/visit-datasets/$DS_ID/pathways?status=LATE_PENDING" | jq 'length')" "1"
check "郑十目标侧就诊归入待定类别" \
  "$(get "/api/visit-datasets/$DS_ID/records" | jq '[.[] | select(.sourcePatientId=="V-004" and .classification=="PENDING_EVENT")] | length')" "1"

# 6.6 无凭据与身份未确定
check "钱十二（无凭据）只能形成可能事件" \
  "$(get "/api/visit-datasets/$DS_ID/records" | jq '[.[] | select(.sourcePatientId=="V-006" and .classification=="PROBABLE_EVENT")] | length')" "2"
check "未知身份记录无法归并（UNRESOLVED_IDENTITY）" \
  "$(get "/api/visit-datasets/$DS_ID/records" | jq '[.[] | select(.sourcePatientId=="P-C-999" and .classification=="UNRESOLVED_IDENTITY")] | length')" "1"
check "身份快照引用阶段标记为部分完成（存在未确定身份）" \
  "$(get "/api/visit-datasets/$DS_ID" | jq -r '.identityStatus')" "PARTIAL"

# 6.7 记录粒度不放大复诊率：周八门诊的挂号+签到两条记录仍只属于 1 个事件
check "周八院区A门诊 2 条来源记录只归入 1 个事件" \
  "$(get "/api/visit-datasets/$DS_ID/records" | jq '[.[] | select(.sourcePatientId=="V-002" and .campusCode=="院区A" and .serviceType=="OUTPATIENT") | .eventId] | unique | length')" "1"

# 6.8 运营报告三类口径
check "运营报告确定路径数 > 0" "$(REPORT "$DS_ID" | jq -r '.confirmed.episodes > 0')" "true"
check "运营报告可能重复记录数 > 0" "$(REPORT "$DS_ID" | jq -r '.possibleDuplicate.records > 0')" "true"
check "运营报告无法归并记录数 > 0" "$(REPORT "$DS_ID" | jq -r '.unmergeable.records > 0')" "true"
check "运营报告待定路径数 > 0" "$(REPORT "$DS_ID" | jq -r '.pendingReview.latePendingPathways > 0')" "true"

# 6.9 版本化：同输入幂等；补传后生成新版本，旧版本不回写
V_SAME=$(post /api/visit-datasets "{}" "$OP1" | jq -r '.datasetVersion')
check "相同输入重复编译幂等（版本不变）" "$V_SAME" "$DS_VER"
post /api/visit-records \
  "{\"sourceSystem\":\"HIS\",\"sourceRecordId\":\"V-009-R1\",\"sourcePatientId\":\"V-001\",\"eventType\":\"LAB\",\"serviceType\":\"LAB\",\"campusCode\":\"采血中心B\",\"occurredAt\":\"2026-11-02T14:40:00Z\",\"encounterKey\":\"ENC-B-101\",\"serviceDetail\":\"肝功能\"}" \
  "$OP1" >/dev/null
V_NEW=$(post /api/visit-datasets "{\"label\":\"补传后新版本\"}" "$OP1" | jq -r '.datasetVersion')
check "补传记录后生成新数据集版本" "$([ "$V_NEW" -gt "$DS_VER" ] && echo yes)" "yes"
check "旧版本记录数未被回写" \
  "$(get "/api/visit-datasets/$DS_ID" | jq -r '.totalRecords')" "27"

# 6.10 快照修订：身份决议后发布新 VISIT 快照，编译生成新版本且引用新快照
NEW_SNAP=$(post /api/snapshots '{"businessType":"VISIT","label":"身份决议后新就诊快照"}' "$OP1" | jq -r '.id')
V_SNAP=$(post /api/visit-datasets "{\"snapshotId\":\"$NEW_SNAP\",\"label\":\"快照修订后\"}" "$OP1" | jq -r '.datasetVersion')
DS_SNAP_ID=$(get "/api/visit-datasets" | jq -r --argjson v "$V_SNAP" '[.[] | select(.datasetVersion==$v)][0].id')
check "快照修订后编译生成新数据集版本" "$([ "$V_SNAP" -gt "$V_NEW" ] && echo yes)" "yes"
check "新版本引用新快照" "$(get "/api/visit-datasets/$DS_SNAP_ID" | jq -r '.snapshotId')" "$NEW_SNAP"
check "新版本沿用该快照固定的图谱版本" \
  "$(get "/api/visit-datasets/$DS_SNAP_ID" | jq -r '.graphVersion')" \
  "$(get "/api/snapshots/$NEW_SNAP" | jq -r '.snapshot.graphVersion')"
check "旧版本仍引用原初始快照（不回写）" \
  "$(get "/api/visit-datasets/$DS_ID" | jq -r '.snapshotId')" "$DS0_SNAPSHOT"

# 6.11 晚到转诊核验：核验后新版本转为确定跨院转诊，且不新增复诊次数
REF_ID=$(get "/api/visit-records?sourceSystem=HIS&sourcePatientId=V-004" | jq -r '[.[] | select(.sourceRecordId=="V-004-R2")][0].id')
post "/api/visit-records/$REF_ID/verify-referral" '{"note":"已核对纸质转诊单"}' "$OP1" >/dev/null
V_VER=$(post /api/visit-datasets '{"label":"转诊核验后"}' "$OP1" | jq -r '.datasetVersion')
DS_VER_ID=$(get "/api/visit-datasets" | jq -r --argjson v "$V_VER" '[.[] | select(.datasetVersion==$v)][0].id')
MATE_V4=$(get "/api/visit-datasets/$DS_VER_ID/records" | jq -r '[.[] | select(.sourcePatientId=="V-004")][0].masterId')
check "核验后晚到转诊路径消失（0 条 LATE_PENDING）" \
  "$(get "/api/visit-datasets/$DS_VER_ID/pathways?status=LATE_PENDING" | jq 'length')" "0"
check "核验后郑十产生已确认跨院转诊路径（REF-104）" \
  "$(get "/api/visit-datasets/$DS_VER_ID/pathways?masterId=$MATE_V4" | jq '[.[] | select(.type=="CROSS_CAMPUS_REFERRAL" and .status=="CONFIRMED" and .referralKey=="REF-104")] | length')" "1"
check "核验后郑十两院区事件合并为 1 个诊疗过程（不新增复诊次数）" \
  "$(get "/api/visit-datasets/$DS_VER_ID/events?masterId=$MATE_V4" | jq '[.[] | .episodeKey] | unique | length')" "1"
check "核验后郑十两个确定事件同属该过程" \
  "$(get "/api/visit-datasets/$DS_VER_ID/events?masterId=$MATE_V4" | jq '[.[] | select(.status=="CONFIRMED" and .pendingReview==false)] | length')" "2"

echo
echo "=========================================================="
echo " 验收回放完成：通过 $pass 项，失败 $fail 项"
echo "=========================================================="
[ "$fail" -eq 0 ]
