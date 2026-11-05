package org.jetlinks.pro.smartpark.repair.service;
import lombok.RequiredArgsConstructor;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
import org.jetlinks.pro.smartpark.repair.api.*;import org.jetlinks.pro.smartpark.repair.entity.QualityRepairLinkEntity;import reactor.core.publisher.Mono;
/** 单独代理的事务边界：重复来源插入失败回滚后，外层才重新读取已提交的关联。 */
@Service @RequiredArgsConstructor public class QualityRepairTransaction {
 private final QualityRepairLinkService links;private final RepairOrderManager manager;
 @Transactional public Mono<QualityRepairInfo> create(QualityRepairLinkEntity link,CreateRepairRequest request){
  return links.insert(link).then(manager.create(request)).flatMap(order->{link.setOrderId(order.getId());return links.updateById(link.getId(),link).thenReturn(new QualityRepairInfo(order.getId(),order.getNumber(),order.getStatus(),order.getApplicationId()));});
 }
}
