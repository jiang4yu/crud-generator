package com.foobar.generator.config;

import com.foobar.generator.constant.GeneratorConst;
import com.foobar.generator.info.DbUtilInfo;
import com.foobar.generator.info.TemplateInfo;
import com.foobar.generator.util.JsonUtils;
import com.foobar.generator.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置
 *
 * @author yin
 */
public class GeneratorConfig {

    /**
     * 默认时区
     */
    public static final String DEFAULT_TIME_ZONE = "GMT+8";

    /**
     * 数据库实现类映射
     */
    public static final Map<String, DbUtilInfo> dbUtilMap = new HashMap<>();

    /**
     * 核心模板
     */
    public final static List<TemplateInfo> coreTemplateList = new ArrayList<>();

    /**
     * 其它模板
     */
    public final static List<TemplateInfo> otherTemplateList = new ArrayList<>();

    static {
        List<DbUtilInfo> dbUtilInfoList = JsonUtils.readResourceAsList("dbutil-config.json", DbUtilInfo.class);
        if (dbUtilInfoList == null || dbUtilInfoList.isEmpty()) {
            throw new RuntimeException("数据库类映射配置文件为空!");
        }
        dbUtilInfoList.forEach(d -> {
            if (d == null || StringUtils.isEmpty(d.getType()) || StringUtils.isEmpty(d.getClassName())) {
                return;
            }
            dbUtilMap.put(d.getType(), d);
        });

        List<TemplateInfo> templateInfoList = JsonUtils.readResourceAsList("template-config.json", TemplateInfo.class);
        if (templateInfoList == null || templateInfoList.isEmpty()) {
            throw new RuntimeException("模板配置文件为空!");
        }
        templateInfoList.forEach(t -> {
            if (t == null) {
                return;
            }
            if (GeneratorConst.YES == t.getIsCore()) {
                coreTemplateList.add(t);
            } else {
                otherTemplateList.add(t);
            }
        });
    }

}
