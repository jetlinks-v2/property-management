package org.jetlinks.pro.patrol.entity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Table;

/** 巡更标准配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_standard")
@Asset(type = "patrolStandard")
public class PatrolStandardEntity extends PatrolConfigEntity {
    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @NotEmpty
    @Size(max = 100)
    @Valid
    private List<@NotNull CheckItem> items = new ArrayList<>();

    /** 历史任务保存此结构快照，不随标准编辑改变。 */
    public record CheckItem(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 128) String title,
            boolean required,
            @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 128) String> options) {}
}
