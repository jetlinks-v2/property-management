package org.jetlinks.pro.patrol.entity;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import javax.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import javax.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.jetlinks.pro.patrol.domain.PatrolRule;
import java.sql.JDBCType;
import javax.persistence.Column;

/** 巡更内部点位等级，通过应用资产边界授权并以停用保留历史。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_level", indexes = @javax.persistence.Index(name = "pt_level_project", columnList = "scopeKey,projectId,ownerOrganizationId"))
@Asset(type = "patrolLevel")
@JsonIgnoreProperties(value = "ruleSummary", allowGetters = true)
public class PatrolLevelEntity extends PatrolRemovableConfigEntity {
    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @NotNull
    private PatrolRule rule;

    /** 列表和导出使用同一份只读展示值；写入始终以原始 rule 为准。 */
    @Transient
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> getRuleSummary() {
        return PatrolRuleSummary.rows(rule);
    }
}
