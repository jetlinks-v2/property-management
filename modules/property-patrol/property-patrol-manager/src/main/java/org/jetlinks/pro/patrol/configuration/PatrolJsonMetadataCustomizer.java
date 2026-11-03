package org.jetlinks.pro.patrol.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;

import org.hswebframework.ezorm.rdb.codec.JsonValueCodec;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.hswebframework.web.crud.configuration.TableMetadataCustomizer;
import org.springframework.stereotype.Component;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Set;

/** JSON 列与 HTTP 使用同一标准 mapper，包括日期键与 record；不修改框架的全局默认 mapper。 */
@Component
@AllArgsConstructor
public class PatrolJsonMetadataCustomizer implements TableMetadataCustomizer {
    private final ObjectMapper mapper;

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
        }
    }
}
