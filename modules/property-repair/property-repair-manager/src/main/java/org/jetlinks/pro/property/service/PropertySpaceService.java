package org.jetlinks.pro.property.service;

import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.entity.PropertySpaceEntity;
/** 共享的 Cloud 空间投影；业务项目归属为共享扩展，源同步不覆盖。 */
@Service("repairPropertySpaceService")
public class PropertySpaceService extends GenericReactiveCrudService<PropertySpaceEntity,String> {}
