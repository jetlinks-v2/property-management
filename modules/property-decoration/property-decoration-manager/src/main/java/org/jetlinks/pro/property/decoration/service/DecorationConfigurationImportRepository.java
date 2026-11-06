package org.jetlinks.pro.property.decoration.service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.decoration.entity.DecorationConfigurationImportEntity;
import org.springframework.stereotype.Service;

/** 仅由导入编排和事务写入器访问的成功收据存储。 */
@Service
public class DecorationConfigurationImportRepository extends GenericReactiveCrudService<DecorationConfigurationImportEntity, String> {}
