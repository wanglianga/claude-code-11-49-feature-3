package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 诊疗过程（episode）：由"已确认跨院转诊"连接起来的一组确定性事件。
 *
 * <p>跨院转诊属于同一个连续诊疗过程，因此复诊次数按 episode 计，而不是按事件或
 * 来源记录计——这样既不会把转院拆成两次复诊，也不会因签到、检验、处方等
 * 记录粒度不同而虚增复诊。</p>
 */
@Entity
@Table(name = "visit_episode")
public class VisitEpisode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID datasetId;

    @Column(nullable = false)
    public UUID masterId;

    /** 数据集内可读编号，如 EP0001 */
    @Column(nullable = false, length = 16)
    public String episodeKey;

    public Instant startAt;

    public Instant endAt;

    @Column(nullable = false)
    public int eventCount;

    @Column(nullable = false)
    public int campusCount;

    /** 逗号分隔的院区编码，便于直接出报告 */
    @Column(length = 200)
    public String campuses;

    /** false = 该过程仍处于待定（跨院凭据缺失或转诊单晚到），不计入确定复诊 */
    @Column(nullable = false)
    public boolean confirmed = true;
}
