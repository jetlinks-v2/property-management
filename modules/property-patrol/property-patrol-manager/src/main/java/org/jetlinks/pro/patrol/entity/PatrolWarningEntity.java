package org.jetlinks.pro.patrol.entity;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import javax.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import javax.persistence.Column;

/** 巡更内部异常预警配置，通过应用资产边界授权并以停用保留历史。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_warning", indexes = @javax.persistence.Index(name = "pt_warning_project", columnList = "scopeKey,projectId,ownerOrganizationId"))
@Asset(type = "patrolWarning")
public class PatrolWarningEntity extends PatrolRemovableConfigEntity {
    /** 与原预警名称的 30 字输入限制一致，不改变历史列宽。 */
    @Override
    @Size(max = 30)
    public String getName() {
        return super.getName();
    }

    /** 与原 PatrolAbnormalWarningServiceImpl 的分类、条件保持一致。 */
    @Column(length = 16, nullable = false)
    @NotBlank
    @Pattern(regexp = "route|person|time")
    private String triggerParentCode;

    @Column(length = 32, nullable = false)
    @NotBlank
    @Pattern(regexp = "route_missed_point|person_absent|time_interval")
    private String triggerChildCode;

    @Column(length = 64, updatable = false)
    private String warningCode;

    @Column(length = 7, nullable = false)
    @NotBlank
    @Pattern(regexp = "#[0-9a-fA-F]{6}")
    private String colorHex = "#409EFF";
}
