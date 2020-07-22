package ${table.pkgName};

import java.io.Serializable;
import javax.persistence.*;
import tk.mybatis.mapper.annotation.KeySql;
import tk.mybatis.mapper.annotation.Version;
<#list table.imports as imp>
import ${imp};
</#list>

/**
 * ${table.comments}实体类
 * 适用于Mybatis通用Mapper
 * @author ${table.author!''}
 */
@Table(name = "${table.name}")
public class ${table.javaClassName}DO implements Serializable {
<#include "./public/serialVersionUID.ftl"/>

<#list table.columns as column>
    /**
     * ${column.columnComment!''}
     */<#if column.isPrimaryKey == 1>
    @Id
    <#if table.dbType == 'oracle'>@GeneratedValue(strategy = GenerationType.IDENTITY, generator = "select SEQ_${table.name}.nextval from dual")</#if><#if table.dbType == 'mysql'>@KeySql(useGeneratedKeys = true)</#if><#if table.dbType == 'sqlserver'>@GeneratedValue(strategy = GenerationType.IDENTITY)</#if></#if><#if table.versionColumn??><#if table.versionColumn == column.columnName>
    @Version</#if></#if>
    private ${column.columnJavaType} ${column.columnCamelNameLower};

</#list>
<#include "./public/getterAndSetter.ftl"/>
}