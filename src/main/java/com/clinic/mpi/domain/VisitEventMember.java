package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** 事件成员：一条来源记录为何被归入该事件（归并规则 + 置信度）。 */
@Entity
@Table(name = "visit_event_member")
public class VisitEventMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public UUID datasetId;

    @Column(nullable = false)
    public UUID eventId;

    @Column(nullable = false)
    public UUID recordId;

    @Column(nullable = false, length = 32)
    public String sourceSystem;

    @Column(nullable = false, length = 64)
    public String sourceRecordId;

    @Column(nullable = false, length = 16)
    public String eventType;

    @Column(nullable = false, length = 32)
    public String serviceType;

    @Column(nullable = false, length = 32)
    public String campusCode;

    public Instant occurredAt;

    /** ENCOUNTER_KEY / REFERRAL_CHAIN / TIME_WINDOW / TIME_WINDOW_ONLY / DATE_ONLY_GROUP */
    @Column(nullable = false, length = 32)
    public String mergeRule;

    /** CONFIRMED / PROBABLE_TIME_WINDOW / TIME_WINDOW_ONLY / DATE_ONLY */
    @Column(nullable = false, length = 32)
    public String membershipStatus;

    @Column(length = 500)
    public String note;

    @Column(nullable = false)
    public int ordinal;
}
