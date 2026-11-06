package org.jetlinks.pro.property.decoration.service;
import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.decoration.entity.DecorationTaskEntity;
/** Task 响应式存储；外部业务入口由应用服务拥有。 */
@Service
public class DecorationTaskRepository extends GenericReactiveCrudService<DecorationTaskEntity,String> {}
