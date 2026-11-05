package org.jetlinks.pro.property.base.organization;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 供财务、巡更等模块使用的只读组织树节点，不包含实体的审计和持久化字段。
 * 未标注的组织也保留，组织类型为 null；children 只包含调用者有权查看的节点。
 *
 * @see org.jetlinks.pro.property.base.command.QueryPropertyOrganizationTreeCommand
 * @since 2.20.0
 */
@Getter
@Setter
@Schema(description = "物业组织树节点")
public class PropertyOrganizationInfo implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @Schema(description = "SaaS 原组织 ID")
    private String id;

    @Schema(description = "组织名称")
    private String name;

    @Schema(description = "组织编码")
    private String code;

    @Schema(description = "上级组织 ID；上级可能不在调用者的数据权限范围内")
    private String parentId;

    @Schema(description = "同级排序值")
    private Long sortIndex;

    @Schema(description = "已标注的物业类型，null 表示未标注；不继承父级类型")
    private OrganizationType organizationType;

    @Schema(description = "项目章平台文件 ID；未配置或非项目为空")
    private String projectSealFileId;

    @Schema(description = "有权查看的下级组织，叶节点为空列表")
    private List<PropertyOrganizationInfo> children;
}
