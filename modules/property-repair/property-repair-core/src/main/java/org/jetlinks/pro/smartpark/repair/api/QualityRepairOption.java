package org.jetlinks.pro.smartpark.repair.api;
import java.io.Serializable;import java.util.Map;
/** 报修发起表单的授权配置快照。 */
public record QualityRepairOption(String id,String name,String kind,String parentId,Map<String,Object> configuration) implements Serializable {}
