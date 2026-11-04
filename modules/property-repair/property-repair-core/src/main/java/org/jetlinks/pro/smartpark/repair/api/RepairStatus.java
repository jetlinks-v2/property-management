package org.jetlinks.pro.smartpark.repair.api;

import lombok.Getter;
import lombok.AllArgsConstructor;
/** 延续旧报事的状态编号；待接单、待跟进、处理中和归档分别保留。 */
@Getter @AllArgsConstructor
public enum RepairStatus {
 NO_DISTRIBUTED(0,"待分派"),HANDLING(1,"处理中"),FINISHED(2,"已完结"),ABANDONED(3,"已废弃"),CLOSED(4,"已关闭"),ARCHIVED(5,"已归档"),WAIT_ACCEPT(6,"待接单"),WAIT_FOLLOW(7,"待跟进"),WAIT_CALLBACK(8,"待回访"),WAIT_AUDIT(9,"待审核");
 private final int value;
 private final String text;
}
