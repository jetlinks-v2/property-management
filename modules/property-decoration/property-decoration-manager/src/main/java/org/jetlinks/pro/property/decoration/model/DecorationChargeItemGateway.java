package org.jetlinks.pro.property.decoration.model;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import reactor.core.publisher.Mono;

/** 收费服务的只读目录契约；实现须保留调用者认证与应用资产范围，只返回项目启用科目。 */
public interface DecorationChargeItemGateway {
    record ChargeItem(String id, String name) {}
    Mono<PagerResult<ChargeItem>> query(String projectId, QueryParamEntity query);
}
