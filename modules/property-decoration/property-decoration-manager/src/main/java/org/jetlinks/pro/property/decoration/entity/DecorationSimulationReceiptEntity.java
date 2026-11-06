package org.jetlinks.pro.property.decoration.entity;

import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import javax.persistence.Column;
import javax.persistence.Table;

/** 仅本机模拟提供方的持久回执；与真实资金回执分表，幂等键绑定完整业务请求。 */
@Getter
@Setter
@Table(name = "pd_simulation_receipt")
public class DecorationSimulationReceiptEntity extends GenericEntity<String> {
    @Column(length = 32, nullable = false) private String operation;
    @Column(length = 64, nullable = false) private String fingerprint;
    @Column(length = 16384, nullable = false) private String result;
    @Column(nullable = false) private Long createTime;
}
