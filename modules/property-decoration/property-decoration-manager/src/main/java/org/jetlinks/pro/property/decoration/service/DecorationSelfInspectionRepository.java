package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.decoration.entity.DecorationSelfInspectionEntity;
import org.springframework.stereotype.Service;

/** 自检快照存储，写入由申请事务拥有。 */
@Service
public class DecorationSelfInspectionRepository extends GenericReactiveCrudService<DecorationSelfInspectionEntity, String> {}
