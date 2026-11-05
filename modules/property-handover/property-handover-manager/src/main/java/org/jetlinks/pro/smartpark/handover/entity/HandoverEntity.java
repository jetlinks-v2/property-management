package org.jetlinks.pro.smartpark.handover.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;

/** 项目业务记录保留必填项目约束；应用共享目录不继承此类。 */
@Getter
@Setter
public abstract class HandoverEntity extends HandoverApplicationEntity {
    @Column(length = 64, nullable = false, updatable = false)
    private String projectId;
}
