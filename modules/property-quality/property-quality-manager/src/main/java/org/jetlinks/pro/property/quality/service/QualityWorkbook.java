package org.jetlinks.pro.property.quality.service;
import org.apache.poi.ss.usermodel.*;
import org.hswebframework.web.exception.BusinessException;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
/** 迁移旧Excel表头、空白继承和逐行校验；整表通过后才允许事务写入。 */
public final class QualityWorkbook {
 private QualityWorkbook(){}
 public record ImportRow(int row,String project,String line,String subject,String content,String requirement,String photo,Integer score,String deduction,Integer frequency,int status){}
 public static List<String> headers(String target,int level){return switch(target){case "subjects"->List.of("条线名称","科目名称","状态");case "basic"->List.of("所属条线","所属科目","检查名称","检查内容","照片要求","状态");default->level==2?List.of("所属项目","所属条线","所属科目","检查名称","分值","扣分标准","巡检频次","状态"):List.of("所属项目","所属条线","所属科目","检查名称","分值","扣分标准","状态");};}
 public static List<ImportRow> parse(byte[] bytes,String target,int level){
  QualityScope.require(Set.of("subjects","basic","task").contains(target),"import_target");
  try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
   QualityScope.require(book.getNumberOfSheets()>0,"import_sheet");var sheet=book.getSheetAt(0);QualityScope.require(sheet.getLastRowNum()<=1020,"import_limit");var fmt=new DataFormatter();Map<String,Integer> columns=new LinkedHashMap<>();int header=-1;
   var required=new ArrayList<>(headers(target,level));required.remove("扣分标准");
   for(int i=0;i<=Math.min(19,sheet.getLastRowNum());i++){var row=sheet.getRow(i);columns.clear();if(row==null)continue;for(var cell:row)columns.put(fmt.formatCellValue(cell).replace("*","").replace("＊","").trim(),cell.getColumnIndex());if(columns.keySet().containsAll(required)){header=i;break;}}
   QualityScope.require(header>=0,"import_header");List<ImportRow> rows=new ArrayList<>();String project="",line="",subject="";
   for(int i=header+1;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);if(row==null)continue;Map<String,String> data=new LinkedHashMap<>();for(var e:columns.entrySet()){var cell=row.getCell(e.getValue());QualityScope.require(cell==null||cell.getCellType()!=CellType.FORMULA,"import_formula");data.put(e.getKey(),cell==null?"":fmt.formatCellValue(cell).trim());}if(data.values().stream().allMatch(String::isBlank))continue;
    project=nonempty(data.get("所属项目"),project);line=nonempty(data.get(target.equals("subjects")?"条线名称":"所属条线"),line);subject=nonempty(data.get(target.equals("subjects")?"科目名称":"所属科目"),subject);
    try{QualityScope.require(!line.isBlank()&&!subject.isBlank(),"import_required");String content=data.getOrDefault("检查名称","");String req=data.getOrDefault("检查内容",""),photo=data.getOrDefault("照片要求","");Integer score=null,frequency=null;
     if(!target.equals("subjects"))QualityScope.require(!content.isBlank(),"content_required");
     if(target.equals("basic"))QualityScope.require(!req.isBlank()&&!photo.isBlank(),"content_required");
     if(target.equals("task")){QualityScope.require(!project.isBlank(),"project_required");score=new BigDecimal(data.get("分值")).intValueExact();QualityConfigurationRules.score(score);if(level==2)frequency=switch(data.getOrDefault("巡检频次","")){case "1","日","每日","每天"->1;case "2","周","每周"->2;case "3","月","每月"->3;default->throw new IllegalArgumentException("frequency");};}
     int status=switch(data.getOrDefault("状态","")){case "","1","启用"->1;case "0","禁用","停用"->0;default->throw new IllegalArgumentException("status");};
     rows.add(new ImportRow(i+1,project,line,subject,content,req,photo,score,data.getOrDefault("扣分标准",data.getOrDefault("扣分项","")),frequency,status));
    }catch(RuntimeException e){throw new BusinessException("error.quality.import_row",400,"row "+(i+1));}
   }
   QualityScope.require(!rows.isEmpty(),"import_empty");return rows;
  }catch(IOException e){throw new BusinessException("error.quality.import_invalid",400);}
 }
 private static String nonempty(String value,String old){return value==null||value.isBlank()?old:value;}
}
