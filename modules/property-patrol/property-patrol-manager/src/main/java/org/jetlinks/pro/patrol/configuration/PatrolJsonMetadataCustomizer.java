package org.jetlinks.pro.patrol.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;

import lombok.AllArgsConstructor;

import org.hswebframework.ezorm.rdb.codec.JsonValueCodec;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.springframework.stereotype.Component;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.beanutils.Converter;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Set;

/** 巡更列编码：JSON 复用 HTTP mapper，业务日期按 ISO 文本持久化，不修改框架全局编码。 */
@Component
@AllArgsConstructor
public class PatrolJsonMetadataCustomizer implements TableMetadataCustomizer {
    private final ObjectMapper mapper;

    @PostConstruct
    public void registerBusinessDateConverter() {
        var converters = BeanUtilsBean.getInstance().getConvertUtils();
        // FastObjectPropertyOperator 通过此扩展点复制列值；LocalDate 是不可变值，不能作为 Bean 实例化。
        // 仅补充缺失的转换器，保留平台已注册的日期转换规则。
        if (converters.lookup(LocalDate.class) == null) {
            converters.register(new Converter() {
                @Override
                public <T> T convert(Class<T> type, Object value) {
                    return value == null ? null : type.cast(value instanceof LocalDate
                            ? value : LocalDate.parse(value.toString()));
                }
            }, LocalDate.class);
        }
    }

    @Override
    public void customColumn(
            Class<?> entityType,
            PropertyDescriptor descriptor,
            Field field,
            Set<Annotation> annotations,
            RDBColumnMetadata column) {}

    @Override
    public void customTable(Class<?> entityType, RDBTableMetadata table) {
        if (!entityType.getPackageName().equals("org.jetlinks.pro.patrol.entity")) {
            return;
        }
        for (RDBColumnMetadata column : table.getColumns()) {
            if (column.getValueCodec() instanceof JsonValueCodec codec) {
                codec.setMapper(mapper);
            }
            // DateTimeCodec.encode 会把日期条件转为 timestamp，不能用于已发布的 VARCHAR(10) 日期列。
            if ((entityType == org.jetlinks.pro.patrol.entity.PatrolRosterEntity.class
                    && "businessDate".equals(column.getAlias()))
                    || (entityType == org.jetlinks.pro.patrol.entity.PatrolPlanEntity.class
                    && Set.of("effectiveFrom", "effectiveTo").contains(column.getAlias()))) {
                column.setValueCodec(new org.hswebframework.ezorm.core.ValueCodec<Object, java.time.LocalDate>() {
                    public Object encode(Object value) {
                        return value instanceof java.time.LocalDate date ? date.toString() : value;
                    }
                    public java.time.LocalDate decode(Object value) {
                        return value == null ? null : value instanceof java.time.LocalDate date
                                ? date : java.time.LocalDate.parse(value.toString());
                    }
                });
            }
        }
    }
}
