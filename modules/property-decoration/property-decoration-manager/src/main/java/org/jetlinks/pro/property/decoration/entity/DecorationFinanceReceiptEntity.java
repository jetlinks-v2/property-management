package org.jetlinks.pro.property.decoration.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.Column;
import javax.persistence.Table;
import javax.persistence.Index;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import java.sql.JDBCType;
import org.jetlinks.pro.property.decoration.model.DecorationModels;

/** 装修自有数据；仅通过受控业务服务维护，不暴露通用写入和物理删除。 */
@Getter @Setter @EnableEntityEvent
@Table(name="pd_finance_receipt", indexes={@Index(name="uk_pd_finance_reference", columnList="receiptKey", unique=true)})
public class DecorationFinanceReceiptEntity extends GenericEntity<String> {
    @Column(length=64, nullable=false, updatable=false) private String decorationId;
    @Column(length=64, nullable=false, updatable=false) private String receiptKey;
    @Column(length=16, updatable=false) private String direction;
    @Column(length=128, updatable=false) private String provider;
    @Column(length=128, updatable=false) private String reference;
    @Column private Long createTime;
}
