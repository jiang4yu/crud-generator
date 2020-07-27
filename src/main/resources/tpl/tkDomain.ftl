package ${table.pkgName};

import java.io.Serializable;
import javax.persistence.*;
import tk.mybatis.mapper.annotation.KeySql;
<#if table.dbType == 'oracle'>
import tk.mybatis.mapper.code.ORDER;
</#if>
import tk.mybatis.mapper.annotation.Version;
<#list table.imports as imp>
import ${imp};
</#list>

/**
 * ${table.comments}实体类
 * (适用于Mybatis通用Mapper;该文件自动生成，请勿修改)
 *
 * @author ${table.author!''}
 */
@Table(name = "${table.name}")
public class ${table.javaClassName}DO implements Serializable {
<#include "./public/serialVersionUID.ftl"/>

<#list table.columns as column>
    /**
     * ${column.columnComment!''}
     */<#if column.isPrimaryKey == 1>
    @Id<#if table.dbType == 'oracle'>
    @KeySql(sql = "select <#if table.sequenceName??>${table.sequenceName}<#else>SEQ_${table.name}</#if>.nextval from dual", order = ORDER.BEFORE)</#if><#if table.dbType == 'mysql'>
    @KeySql(useGeneratedKeys = true)</#if><#if table.dbType == 'sqlserver'>@GeneratedValue(strategy = GenerationType.IDENTITY)</#if><#if table.dbType == 'postgresql'>@KeySql(useGeneratedKeys = true)
    @Column(name = "${column.columnCamelNameLower}", insertable = false)</#if></#if><#if table.versionColumn??><#if table.versionColumn == column.columnName>
    @Version</#if></#if>
    private ${column.columnJavaType} ${column.columnCamelNameLower};

</#list>
<#include "./public/getterAndSetter.ftl"/>
}