package org.jetlinks.pro.patrol.entity;

import jakarta.validation.constraints.NotBlank;
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

/** 巡更岗位配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_post")
@Asset(type = "patrolPost")
public class PatrolPostEntity extends PatrolConfigEntity {
    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @NotNull
    @Size(max = 200)
    private List<@NotBlank @Size(max = 64) String> userIds = new ArrayList<>();
}
