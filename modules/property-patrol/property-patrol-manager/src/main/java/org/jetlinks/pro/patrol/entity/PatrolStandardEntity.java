package org.jetlinks.pro.patrol.entity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.jetlinks.pro.assets.annotation.Asset;

import java.sql.JDBCType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Table;

/** 巡更标准配置；只保存业务关系，身份与授权仍由 SaaS 管理。 */
@Getter
@Setter
@EnableEntityEvent
@Table(name = "pt_standard", indexes = @javax.persistence.Index(name = "pt_standard_project", columnList = "scopeKey,projectId,ownerOrganizationId"))
@Asset(type = "patrolStandard")
public class PatrolStandardEntity extends PatrolRemovableConfigEntity {
    /** 旧巡更标准名称最多 30 字；保留历史列宽，不截断已有名称。 */
    @Override
    @Size(max = 30)
    public String getName() {
        return super.getName();
    }

    @Column
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR, javaType = String.class)
    @NotEmpty
    @Size(max = 100)
    @Valid
    private List<@NotNull CheckItem> items = new ArrayList<>();

    public enum ItemType { number, text, choice, image }

    /** 历史任务保存完整标准；缺少 type 的已部署选项标准仍按 choice 解释。 */
    public record CheckItem(
            @NotBlank @Size(max = 64) String id,
            @NotBlank @Size(max = 200) String title,
            boolean required,
            @Size(max = 50) List<@NotBlank @Size(max = 128) String> options,
            ItemType type,
            @Digits(integer = 18, fraction = 6) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lowerLimit,
            @Digits(integer = 18, fraction = 6) @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal upperLimit,
            @Size(max = 2000) String referenceText,
            // 保留已部署的请求/快照字段，实际选项异常规则以 options 首项为准。
            @Size(max = 50) List<@NotBlank @Size(max = 128) String> qualifiedOptions,
            boolean photoRequired,
            @Size(max = 64) String exampleFileId) {
        public CheckItem {
            type = type == null ? ItemType.choice : type;
            options = options == null ? List.of() : List.copyOf(options);
            qualifiedOptions = qualifiedOptions == null ? List.of() : List.copyOf(qualifiedOptions);
        }

        public CheckItem(String id, String title, boolean required, List<String> options) {
            this(id, title, required, options, ItemType.choice, null, null, null, List.of(), false, null);
        }
    }
}
