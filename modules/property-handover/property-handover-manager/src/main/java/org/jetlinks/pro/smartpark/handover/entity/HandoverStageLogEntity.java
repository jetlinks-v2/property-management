package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Table;

/** Immutable stage operation history; lifecycle survives record deletion. */
@Getter
@Setter
@Table(name = "sp_handover_stage_log")
public class HandoverStageLogEntity extends HandoverEntity {
    @Column(length = 32)
    private String stage;

    @Column(length = 32)
    private String action;

    @Column(length = 64)
    private String operatorId;

    @Column(length = 128)
    private String operatorName;

    @Column private Long time;
}
