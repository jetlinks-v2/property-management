package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.ExtendableEntity;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.jetlinks.pro.assets.annotation.Asset;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;
/** 当前应用内按业务项目独立维护的报事字典和流程配置。 */
@Getter @Setter @EnableEntityEvent @Asset(type="repairConfiguration")
@Table(name="sp_repair_configuration",indexes={
 @Index(name="idx_repair_config_project_kind",columnList="applicationId,businessProjectId,kind"),
 @Index(name="uk_repair_satisfaction_score",columnList="applicationId,businessProjectId,satisfactionScore",unique=true),
 @Index(name="uk_repair_satisfaction_level",columnList="applicationId,businessProjectId,satisfactionLevel",unique=true),
 @Index(name="uk_repair_qr_code",columnList="qrCode",unique=true),
 @Index(name="uk_repair_form_options",columnList="applicationId,formOptionsProject",unique=true),
 @Index(name="uk_repair_level_coefficient",columnList="applicationId,businessProjectId,levelCoefficient",unique=true),
 @Index(name="uk_repair_commission_project",columnList="applicationId,commissionProject",unique=true),
 @Index(name="uk_repair_category_name",columnList="applicationId,businessProjectId,categoryParentKey,categoryNameKey",unique=true)})
public class RepairConfigurationEntity extends ExtendableEntity<String> implements org.hswebframework.web.api.crud.entity.RecordCreationEntity {
 @Column(length=64,updatable=false) private String creatorId;
 @Column(length=128,updatable=false) private String creatorName;
 @Column(length=64,nullable=false,updatable=false) private String applicationId;
 @Column(length=64,updatable=false) @NotBlank private String businessProjectId;
 @Column(length=32,nullable=false) @NotBlank private String kind;
 @Column(length=128,nullable=false) @NotBlank private String name;
 @Column(length=64) private String parentId;
 // 同项目同父分类名称唯一；其他字典及未编辑的历史记录保持NULL，不自动改名。
 @Column(length=64) private String categoryParentKey;
 @Column(length=128) private String categoryNameKey;
 // 满意度专用唯一键；其他配置保持 NULL，数据库约束防止并发保存重复分值/级别。
 @Column private Long satisfactionScore;
 // 等级系数由配置校验边界填充；旧记录不自动回填，以保留已有测试数据和重复值。
 @Column private Integer levelCoefficient;
 @Column(length=128) private String satisfactionLevel;
 // 公开链接唯一定位配置；非二维码配置为NULL，禁止猜测客户端传来的租户或应用。
 @Column(length=60) private String qrCode;
 @Column(length=64) private String qrPublisherId;
 // 仅表单显示设置填入业务项目ID；唯一键保证同应用每项目一份，其他配置保持NULL。
 @Column(length=64) private String formOptionsProject;
 // 同一应用每业务项目一份抽佣配置，停用的配置仍占用唯一键。
 @Column(length=64) private String commissionProject;
 // 流程与分类共用记录，但创建人及更新时间独立，分类编辑不得改写流程历史。
 @Column(length=128) private String flowCreatorName;
 @Column private Long flowModifyTime;
 @Column private Boolean enabled;
 @Column private Integer sortIndex;
 @Column @JsonCodec @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
 private Map<String,Object> configuration;
 @Column private Long createTime;
 @Column private Long modifyTime;
}
