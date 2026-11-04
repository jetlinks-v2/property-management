package org.jetlinks.pro.smartpark.repair.service;

import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.api.PublicRepairRequest;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 业务项目的七个旧表单开关；隐藏项不采信客户端内容，二维码空间授权不受显示开关影响。 */
public record RepairFormOptions(Map<String, Boolean> values) {
    public static final List<String> KEYS=List.of("showReportPosition","showReportDetailLocation","showReportLabel",
        "showReportDescriptionChoice","showReportUrgency","showProblemDescription","showMediaUpload");

    public static RepairFormOptions from(Map<String, ?> data) {
        Map<String,Boolean> flags=new LinkedHashMap<>();
        KEYS.forEach(key->{
            Object value=data.getOrDefault(key,null);
            RepairFlowRules.require(value==null||value instanceof Boolean,"form_options_invalid");
            flags.put(key,!Boolean.FALSE.equals(value));
        });
        RepairFlowRules.require(!flags.get("showReportDescriptionChoice")||flags.get("showReportLabel"),"form_options_labels_required");
        RepairFlowRules.require(flags.get("showReportDescriptionChoice")||flags.get("showProblemDescription"),"form_options_description_required");
        return new RepairFormOptions(Map.copyOf(flags));
    }

    public boolean show(String key) { return values.get(key); }

    public void normalize(CreateRepairRequest input) {
        if(!show("showReportPosition")){input.setPositionType(1);input.setSpaceId(null);}
        if(!show("showReportDetailLocation"))input.setLocation(null);
        if(!show("showReportUrgency"))input.setUrgency(null);
        if(!show("showReportLabel"))input.setLabelIds(List.of());
        if(!show("showReportDescriptionChoice"))input.setDescriptionChoiceIds(List.of());
        if(!show("showMediaUpload"))input.setAttachmentIds(List.of());
        requireLabels(input.getLabelIds());
    }

    /** 公开入口始终保留二维码范围内的空间，不允许显示开关扩大匿名能力。 */
    public void normalize(PublicRepairRequest input) {
        if(!show("showReportDetailLocation"))input.setLocation(null);
        if(!show("showReportUrgency"))input.setUrgency(null);
        if(!show("showReportLabel"))input.setLabelIds(List.of());
        if(!show("showReportDescriptionChoice"))input.setDescriptionChoiceIds(List.of());
        if(!show("showMediaUpload"))input.setUploadIds(List.of());
        requireLabels(input.getLabelIds());
    }

    private void requireLabels(List<String> labels) {
        RepairFlowRules.require(!show("showReportLabel")||!labels.isEmpty(),"labels_required");
    }

    /** 描述选项已由入口核验项目、分类和标签关系，此处只生成可信正文并校验长度。 */
    public String description(String submitted,List<String> choices) {
        String description=show("showProblemDescription")?submitted:String.join("\n",choices);
        RepairFlowRules.require(description!=null&&!description.isBlank(),"description_required");
        RepairFlowRules.require(description.length()<=800,"description_too_long");
        return description;
    }
}
