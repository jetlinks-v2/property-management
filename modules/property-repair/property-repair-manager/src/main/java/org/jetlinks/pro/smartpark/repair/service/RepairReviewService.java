package org.jetlinks.pro.smartpark.repair.service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.repair.entity.RepairReviewEntity;
import org.springframework.stereotype.Service;
/** 审核记录持久化，申请及决定均由工单事务编排。 */
@Service
public class RepairReviewService extends GenericReactiveCrudService<RepairReviewEntity,String> { }
