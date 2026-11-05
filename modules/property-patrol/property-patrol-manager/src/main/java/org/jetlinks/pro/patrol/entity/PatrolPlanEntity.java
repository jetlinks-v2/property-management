package org.jetlinks.pro.patrol.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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

/** 点位岗位指派及其巡更规则；未配置标准和规则时仅表示岗位关系，不生成任务。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_plan", indexes = @javax.persistence.Index(name = "pt_plan_project", columnList = "scopeKey,projectId,ownerOrganizationId"))
@Asset(type = "patrolPlan")
public class PatrolPlanEntity extends PatrolConfigEntity {
    @Column(length = 64, nullable = false)
    @NotBlank
    private String pointId;

    /** 岗位规则属于点位在此分类中的关系；已发布的空值由关系迁移解析原点位分类。 */
    @Column(length = 64)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String categoryId;

    @Column(length = 64, nullable = false)
    @NotNull
    private String roleId = "";

    /** 空串表示原系统的全员任务，不创建虚拟应用角色；仍使用原计划资产授权。 */
    public void setRoleId(String roleId) {
        this.roleId = roleId == null ? "" : roleId.trim();
    }

    /** 保存指派时由应用角色目录生成；定时任务使用快照，不冒充用户查询角色或成员。 */
    @Column(length = 256)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String roleName;

    @Column(length = 64)
    private String standardId;

    /** 可复用等级；为空时沿用计划自定义规则，兼容已生成的计划。 */
    @Column(length = 64)
    private String levelId;

    @Column
    private int sortIndex;

    /** 等级名称随规则一起解析，不接受其作为任务规则来源。 */
    private transient String levelName;

    /** 历史计划的人工期限；新岗位规则无需填写，空表示不设该端边界。 */
    @Column(length = 10)
    @DateTimeCodec(format = "yyyy-MM-dd")
    @ColumnType(javaType = String.class)
    private LocalDate effectiveFrom;

    @Column(length = 10)
    @DateTimeCodec(format = "yyyy-MM-dd")
    @ColumnType(javaType = String.class)
    private LocalDate effectiveTo;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private PatrolRule rule;

    @Column(length = 16, nullable = false)
    @NotNull
    @EnumCodec
    @ColumnType(javaType = String.class)
    private ExecutionPolicy.RosterPolicy rosterPolicy = ExecutionPolicy.RosterPolicy.optional;
}
