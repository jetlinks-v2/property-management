package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.DateTimeCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import org.jetlinks.pro.patrol.domain.ExecutionPolicy;
import org.jetlinks.pro.patrol.domain.PatrolRule;

import java.sql.JDBCType;
import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Table;

/** 巡更计划配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_plan")
@Asset(type = "patrolPlan")
public class PatrolPlanEntity extends PatrolConfigEntity {
    @Column(length = 64, nullable = false)
    @NotBlank
    private String pointId;

    @Column(length = 64, nullable = false)
    @NotBlank
    private String postId;

    @Column(length = 64, nullable = false)
    @NotBlank
    private String standardId;

    /** 可复用等级；为空时沿用计划自定义规则，兼容已生成的计划。 */
    @Column(length = 64)
    private String levelId;

    /** 等级名称随规则一起解析，不接受其作为任务规则来源。 */
    private transient String levelName;

    @Column(length = 10, nullable = false)
    @NotNull
    @DateTimeCodec(format = "yyyy-MM-dd")
    @ColumnType(javaType = String.class)
    private LocalDate effectiveFrom;

    @Column(length = 10, nullable = false)
    @NotNull
    @DateTimeCodec(format = "yyyy-MM-dd")
    @ColumnType(javaType = String.class)
    private LocalDate effectiveTo;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @NotNull
    private PatrolRule rule;

    @Column(length = 16, nullable = false)
    @NotNull
    @EnumCodec
    @ColumnType(javaType = String.class)
    private ExecutionPolicy.RosterPolicy rosterPolicy = ExecutionPolicy.RosterPolicy.optional;
}
