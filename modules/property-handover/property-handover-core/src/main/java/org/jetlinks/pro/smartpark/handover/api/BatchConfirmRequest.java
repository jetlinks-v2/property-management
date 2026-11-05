package org.jetlinks.pro.smartpark.handover.api;
import lombok.Getter;
import lombok.Setter;
import java.util.List;
/** 资料清单确认：明确项目、来源和每条版本，整批事务提交。 */
@Getter @Setter
public class BatchConfirmRequest {
 private String projectId;
 private String source;
 private List<Item> items;
 @Getter @Setter public static class Item { private String id; private Integer version; }
}
