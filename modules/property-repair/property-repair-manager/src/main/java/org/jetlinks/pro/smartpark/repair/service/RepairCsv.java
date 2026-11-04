package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Excel可打开的UTF-8 CSV。用户文本统一引用并防止公式执行，不输出令牌和内部上下文。 */
public final class RepairCsv {
    private RepairCsv(){}
    private static final String[] STATES={"待分派","处理中","已完结","已废弃","已关闭","已归档","待接单","待跟进","待回访","待审核"};
    public static String header(){return "\ufeff工单编号,项目名称,报事分类,报事渠道,报事来源,报事位置,房间号,报事标签,报事描述,报事人,联系方式,登记人,责任人,状态,报事时间,接单时间,完成时限,完成时间,归档时间,材料费,人工费,合计费用,责任人抽佣,供方抽佣,物业抽佣,员工满意度,业主满意度,签字业主,签字登记人,业主评价登记人\r\n";}
    public static String row(RepairOrderEntity o){
        return line(o.getNumber(),o.getBusinessProjectName(),o.getCategoryName(),o.getChannelName(),o.getSource(),
            o.getPositionType()==null?null:o.getPositionType()==2?"室内":"公区",o.getSpaceName(),
            o.getLabelNames()==null?null:String.join("、",o.getLabelNames()),o.getDescription(),o.getContactName(),o.getContactPhone(),
            o.getCreatorName(),o.getAssignedUserName(),STATES[o.getStatus()],time(o.getCreateTime()),time(o.getAcceptTime()),
            time(o.getFinishDeadline()),time(o.getFinishTime()),time(o.getArchiveTime()),o.getMaterialAmount(),o.getArtificialAmount(),
            o.getTotalAmount(),o.getPersonAmount(),o.getProviderAmount(),o.getPropertyAmount(),o.getStaffSatisfaction(),
            o.getOwnerSatisfaction(),o.getSignatureOwnerName(),o.getSignatureRecorderId(),o.getOwnerEvaluationRecorderId());
    }
    static String line(Object... values){return Arrays.stream(values).map(RepairCsv::cell).collect(Collectors.joining(","))+"\r\n";}
    static String cell(Object value){
        String text=value==null?"":value.toString();
        if(value instanceof String && !text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0))>=0)text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    static String time(Long value){return value==null?"":DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(value));}
}
