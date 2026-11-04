package org.jetlinks.pro.property.quality.service;
import org.jetlinks.pro.property.quality.entity.QualityConfigurationEntity;
import java.util.*;
import java.math.BigDecimal;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;
/** 从旧配置和模板规则迁移的纯校验；配置引用和授权由 manager 负责。 */
public final class QualityConfigurationRules {
 private QualityConfigurationRules(){}
 public static final Set<String> KINDS=Set.of("category","line","subject","content","inspection-content","template","evaluation","business-type","settings");
 public static void validate(QualityConfigurationEntity r){
  require(KINDS.contains(r.getKind()),"configuration_kind");require(r.getName()!=null&&!r.getName().isBlank()&&r.getName().length()<=128,"name_required");
  require(r.getConfigLevel()!=null&&Set.of(1,2).contains(r.getConfigLevel()),"level_required");require(r.getStatus()!=null&&Set.of(0,1).contains(r.getStatus()),"status_required");
  if(r.getConfigLevel()==2)require(r.getProjectId()!=null&&!r.getProjectId().isBlank(),"project_required");
  Map<String,Object>d=r.getData();require(d!=null,"configuration_required");
  if(r.getKind().equals("subject")||r.getKind().equals("content"))require(r.getParentId()!=null&&!r.getParentId().isBlank(),"parent_required");
  if(r.getKind().equals("content")){require(!text(d,"checkContent").isBlank(),"content_required");require(!text(d,"photoRequirement").isBlank(),"photo_requirement");}
  if(r.getKind().equals("inspection-content")){require(!text(d,"checkContentId").isBlank(),"content_required");score(d.get("score"));if(r.getConfigLevel()==2)require(Set.of(1,2,3).contains(number(d.get("inspectionFrequency"))),"frequency_required");}
  if(r.getKind().equals("evaluation")){require(number(d.get("minScore"))<=number(d.get("maxScore")),"evaluation_range");}
 }
 public static void score(Object value){require(value!=null,"score_required");BigDecimal n=new BigDecimal(value.toString());require(n.scale()<=0&&n.signum()>=0&&n.compareTo(BigDecimal.valueOf(100))<=0,"score_range");}
 public static String text(Map<String,Object>d,String k){return Objects.toString(d.get(k),"");}
 public static int number(Object o){return o==null?0:new BigDecimal(o.toString()).intValueExact();}
 @SuppressWarnings("unchecked") public static List<Map<String,Object>> items(Object o){return o==null?List.of():(List<Map<String,Object>>)o;}
}
