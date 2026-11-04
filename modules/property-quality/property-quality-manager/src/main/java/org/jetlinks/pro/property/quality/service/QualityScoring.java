package org.jetlinks.pro.property.quality.service;
import org.jetlinks.pro.property.quality.entity.QualityRecordEntity;
import java.math.*;import java.util.*;import java.util.stream.Collectors;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;
import static org.jetlinks.pro.property.quality.service.QualityConfigurationRules.*;
/** 保留旧版正分上下限、零分负扣分及公司级条线加权的四位中间精度、整数四舍五入。 */
public final class QualityScoring {
 private QualityScoring(){}
 public static void validateScore(int original,int actual){require(original>=0&&(original==0?actual<=0:actual>=0&&actual<=original),"score_actual_range");}
 public static int total(int level,List<QualityRecordEntity> records,List<Map<String,Object>> weights){
  if(level!=1||weights==null||weights.isEmpty())return records.stream().map(QualityRecordEntity::getScore).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
  BigDecimal total=BigDecimal.ZERO;
  for(var w:weights){int actual=records.stream().filter(r->Objects.equals(r.getSubjectParentId(),text(w,"subjectParentId"))).map(QualityRecordEntity::getScore).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
   total=total.add(BigDecimal.valueOf(actual).multiply(new BigDecimal(w.get("weight").toString())).divide(BigDecimal.valueOf(100),4,RoundingMode.HALF_UP));}
  return total.setScale(0,RoundingMode.HALF_UP).intValueExact();
 }
 public static void validateWeights(int level,List<Map<String,Object>> snapshots,List<Map<String,Object>> weights){
  if(level!=1)return;
  Set<String> lines=snapshots.stream().map(r->text(r,"subjectParentId")).collect(Collectors.toSet());
  require(weights.size()==lines.size(),"weights_required");BigDecimal sum=BigDecimal.ZERO;Set<String> seen=new HashSet<>();
  for(var w:weights){String line=text(w,"subjectParentId");require(lines.contains(line)&&seen.add(line)&&w.get("weight")!=null,"weights_required");var value=new BigDecimal(w.get("weight").toString());require(value.signum()>=0&&value.compareTo(BigDecimal.valueOf(100))<=0,"weight_range");sum=sum.add(value);}
  require(sum.compareTo(BigDecimal.valueOf(100))==0,"weight_total");
 }
}
