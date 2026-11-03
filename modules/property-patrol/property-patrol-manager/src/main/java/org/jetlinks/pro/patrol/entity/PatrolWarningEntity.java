package org.jetlinks.pro.patrol.entity;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;
import javax.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import javax.persistence.Column;

/** 巡更内部异常预警配置，通过应用资产边界授权并以停用保留历史。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_warning")
@Asset(type = "patrolWarning")
public class PatrolWarningEntity extends PatrolConfigEntity {
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
