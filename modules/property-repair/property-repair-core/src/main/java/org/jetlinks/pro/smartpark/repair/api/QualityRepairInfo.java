package org.jetlinks.pro.smartpark.repair.api;
import java.io.Serializable;
/** 返回给品质的关联摘要；工单详情仍通过报修自身的读取权限打开。 */
public record QualityRepairInfo(String id,String number,Integer status,String applicationId) implements Serializable {}
