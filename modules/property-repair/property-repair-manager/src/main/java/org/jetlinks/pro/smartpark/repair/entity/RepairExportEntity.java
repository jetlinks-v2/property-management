package org.jetlinks.pro.smartpark.repair.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import java.sql.JDBCType;
import java.util.List;

/** 导出文件快照及创建人。记录按创建人组织范围展示，下载仍须具有全部原工单的当前读取权限。 */
@Getter @Setter
@Table(name="sp_repair_export",indexes=@Index(name="idx_repair_export_owner",columnList="applicationId,creatorId,createTime"))
public class RepairExportEntity extends GenericEntity<String> {
    @Column(length=64,nullable=false,updatable=false) private String tenantId;
    @Column(length=64,nullable=false,updatable=false) private String applicationId;
    @Column(length=64,nullable=false,updatable=false) private String creatorId;
    @Column(length=128) private String creatorName;
    @Column(length=128) private String name;
    @Column(length=32) private String status;
    @Column private Integer rowCount;
    @Column private Integer sourceCount;
    @Column(length=32) private String exportType;
    @Column private Long createTime;
    @Column private Long completeTime;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(length=64) private String fileId;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column @JsonCodec
    @ColumnType(jdbcType=JDBCType.LONGVARCHAR,javaType=String.class)
    private List<String> orderIds;
}
