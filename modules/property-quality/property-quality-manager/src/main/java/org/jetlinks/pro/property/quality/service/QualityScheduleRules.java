package org.jetlinks.pro.property.quality.service;
import org.jetlinks.pro.property.quality.entity.QualityTaskEntity;
import java.time.*;import java.time.format.DateTimeFormatter;import java.util.*;
import static org.jetlinks.pro.property.quality.service.QualityScope.require;
import static org.jetlinks.pro.property.quality.service.QualityConfigurationRules.*;
/** 作业周期按北京时间计算；日周期完整匹配频次，周/月允许起止范围截断，月计划使用真实日期。 */
public final class QualityScheduleRules {
 public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 public record Slot(long time,long start,long end){}
 private QualityScheduleRules(){}
 public static List<Slot> build(QualityTaskEntity task){
  if(task.getTaskLevel()==2)return project(task);
  if(task.getTaskType()==1){require(task.getReportDeadline()!=null,"deadline_required");long time=task.getFirstGenerateTime()==null?System.currentTimeMillis():task.getFirstGenerateTime();return List.of(new Slot(time,time,task.getReportDeadline()));}
  require(task.getStartTime()!=null&&task.getEndTime()!=null&&task.getStartTime()<task.getEndTime(),"time_range");
  require(task.getIntervalValue()!=null&&task.getIntervalValue()>0&&task.getIntervalUnit()!=null&&task.getIntervalUnit()>=1&&task.getIntervalUnit()<=7,"interval_required");
  require(task.getFirstGenerateTime()!=null&&task.getFirstGenerateTime()>=task.getStartTime()&&task.getFirstGenerateTime()<=task.getEndTime(),"first_time_range");
  if(task.getReportDeadlineValue()!=null)require(task.getReportDeadlineValue()>0&&Set.of("HOUR","DAY").contains(task.getReportDeadlineUnit()),"deadline_required");else require(task.getReportDeadline()!=null,"deadline_required");
  List<Slot> result=new ArrayList<>();ZonedDateTime point=instant(task.getFirstGenerateTime());
  while(point.toInstant().toEpochMilli()<=task.getEndTime()){
   long time=point.toInstant().toEpochMilli();result.add(new Slot(time,time,deadline(task,time)));require(result.size()<=10000,"schedule_limit");
   int n=task.getIntervalValue();point=switch(task.getIntervalUnit()){case 1->point.plusMinutes(n);case 2->point.plusHours(n);case 3->point.plusDays(n);case 4->point.plusWeeks(n);case 5->point.plusMonths(n);case 6->point.plusMonths(n*3L);default->point.plusYears(n);};
  }
  return result;
 }
 public static long deadline(QualityTaskEntity task,long generated){
  if(task.getReportDeadlineValue()==null)return task.getReportDeadline();
  return ("DAY".equals(task.getReportDeadlineUnit())?instant(generated).plusDays(task.getReportDeadlineValue()):instant(generated).plusHours(task.getReportDeadlineValue())).toInstant().toEpochMilli();
 }
 private static List<Slot> project(QualityTaskEntity t){
  require(t.getStartTime()!=null&&t.getEndTime()!=null&&t.getStartTime()<t.getEndTime(),"time_range");require(t.getOperationFrequency()!=null&&t.getOperationFrequency()>0,"frequency_required");
  require(t.getInspectionFrequency()!=null&&Set.of(1,2,3).contains(t.getInspectionFrequency()),"frequency_required");require(t.getScheduleRules()!=null,"schedule_required");
  var start=instant(t.getStartTime()).toLocalDateTime();var end=instant(t.getEndTime()).toLocalDateTime();require(Duration.between(start,end).toDays()<=3660,"schedule_limit");
  List<Slot> result=new ArrayList<>();var rules=t.getScheduleRules();int n=t.getOperationFrequency();
  if(t.getInspectionFrequency()==1){
   List<String> times=strings(rules.get("dayTimes"));require(times.size()==n&&new HashSet<>(times).size()==n,"schedule_frequency");
   for(var day=start.toLocalDate();!day.isAfter(end.toLocalDate());day=day.plusDays(1)){
    List<LocalDateTime> points=new ArrayList<>();for(String time:times)points.add(day.atTime(LocalTime.parse(time)));
    add(t,result,day.atStartOfDay(),day.atTime(23,59,59),points,true);
   }
  }else if(t.getInspectionFrequency()==2){
   var times=items(rules.get("weekTimes"));require(times.size()==n,"schedule_frequency");Set<String> seen=new HashSet<>();
   for(var time:times){int week=number(time.get("weekDay"));require(week>=1&&week<=7&&seen.add(week+":"+text(time,"time")),"schedule_duplicate");LocalTime.parse(text(time,"time"));}
   for(var monday=start.toLocalDate().with(DayOfWeek.MONDAY);!monday.atStartOfDay().isAfter(end);monday=monday.plusWeeks(1)){
    List<LocalDateTime> points=new ArrayList<>();for(var time:times)points.add(monday.plusDays(number(time.get("weekDay"))-1).atTime(LocalTime.parse(text(time,"time"))));
    add(t,result,monday.atStartOfDay(),monday.plusDays(6).atTime(23,59,59),points,false);
   }
  }else{
   List<LocalDateTime> times=strings(rules.get("monthDateTimes")).stream().map(s->LocalDateTime.parse(s,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).toList();
   require(!times.isEmpty()&&times.stream().distinct().count()==times.size(),"schedule_duplicate");
   for(var month=YearMonth.from(start);!month.isAfter(YearMonth.from(end));month=month.plusMonths(1)){
    var current=month;var points=times.stream().filter(p->YearMonth.from(p).equals(current)&&!p.isBefore(start)&&!p.isAfter(end)).toList();require(points.size()<=n,"schedule_frequency");
    add(t,result,month.atDay(1).atStartOfDay(),month.atEndOfMonth().atTime(23,59,59),points,false);
   }
  }
  require(!result.isEmpty(),"schedule_required");result.sort(Comparator.comparingLong(Slot::time));return result;
 }
 private static void add(QualityTaskEntity t,List<Slot> result,LocalDateTime a,LocalDateTime b,List<LocalDateTime> points,boolean full){
  var start=instant(t.getStartTime()).toLocalDateTime();var end=instant(t.getEndTime()).toLocalDateTime();if(a.isBefore(start))a=start;if(b.isAfter(end))b=end;
  var from=a;var to=b;var valid=points.stream().filter(p->!p.isBefore(from)&&!p.isAfter(to)).distinct().sorted().toList();if(full)require(valid.size()==t.getOperationFrequency(),"schedule_frequency");
  for(var p:valid){result.add(new Slot(epoch(p),epoch(from),epoch(to)));require(result.size()<=10000,"schedule_limit");}
 }
 @SuppressWarnings("unchecked")private static List<String> strings(Object o){return o==null?List.of():(List<String>)o;}
 private static ZonedDateTime instant(long value){return Instant.ofEpochMilli(value).atZone(ZONE);}
 private static long epoch(LocalDateTime value){return value.atZone(ZONE).toInstant().toEpochMilli();}
}
