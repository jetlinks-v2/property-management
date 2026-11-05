package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import java.sql.JDBCType;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Index;
import javax.persistence.Table;

/** 巡更点位配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = "categoryIds", allowGetters = true)
@EnableEntityEvent
@Table(
        name = "pt_point",
        indexes = { @Index(name = "pt_point_scope_code", columnList = "scopeKey,code", unique = true),
                @Index(name = "pt_point_project", columnList = "scopeKey,projectId") })
@Asset(type = "patrolPoint")
public class PatrolPointEntity extends PatrolConfigEntity {
    @Column(length = 64, nullable = false)
    @Size(max = 64)
    private String code;

    /** 新增留空沿用导入的PT+点位ID规则；编辑留空保留原编码，避免无意改变已有编号。 */
    public void completeCode(String previousCode) {
        if (code != null && !code.isBlank()) {
            code = code.trim();
        } else if (previousCode != null && !previousCode.isBlank()) {
            code = previousCode;
        } else {
            code = "PT" + getId().replace("-", "").toUpperCase(java.util.Locale.ROOT);
        }
    }

    @Column(length = 64, nullable = false)
    @NotBlank
    private String categoryId;

    /** 当前分类关系的只读投影；旧 categoryId 保留首次归属，不能代表当前全部分类。 */
    @javax.persistence.Transient
    private List<String> categoryIds;

    public List<String> getCategoryIds() {
        return categoryIds == null ? List.of(categoryId) : categoryIds;
    }

    @Column(length = 255)
    @Size(max = 255)
    private String location;

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    private List<String> tagIds = List.of();

    @Column(length = 128)
    @Size(max = 128)
    private String nfcCode;
}
