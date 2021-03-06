package com.foobar.generator.db;

import com.foobar.generator.config.GeneratorConfig;
import com.foobar.generator.constant.DatabaseType;
import com.foobar.generator.constant.GeneratorConst;
import com.foobar.generator.info.ColumnInfo;
import com.foobar.generator.info.DbUtilInfo;
import com.foobar.generator.info.JdbcInfo;
import com.foobar.generator.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SQLServer数据库工具
 *
 * @author yin
 */
public class SqlServerUtil extends AbstractDbUtil {
    private static final Logger logger = LoggerFactory.getLogger(SqlServerUtil.class);

    /**
     * 准备连接
     *
     * @param jdbcInfo JDBC参数
     * @throws Exception
     */
    @Override
    public void prepareConnection(JdbcInfo jdbcInfo) throws Exception {
        this.dbType = DatabaseType.SQLSERVER.getCode();
        this.schemaName = jdbcInfo.getSchema();
        DbUtilInfo dbUtilInfo = GeneratorConfig.dbUtilMap.get(this.dbType);
        Class.forName(dbUtilInfo.getClassName());
        this.jdbcUrl = String.format(dbUtilInfo.getJdbcUrl(), jdbcInfo.getHost(), jdbcInfo.getPort(), jdbcInfo.getSchema());
    }

    /**
     * 获取所有表名
     *
     * @param schemaName
     * @return
     */
    @Override
    public List<String> getAllTableNames(String schemaName) {
        if (schemaName == null) {
            return null;
        }
        String sql = String.format(SQL_MAP.get("QUERY_TABLE_NAMES"), schemaName.toLowerCase());
        List<String> resultList = this.selectList(sql);
        if (resultList != null && !resultList.isEmpty()) {
            return resultList.stream().map(String::toLowerCase).collect(Collectors.toList());
        } else {
            return resultList;
        }
    }

    /**
     * 获取字段信息
     *
     * @param tableName
     * @return
     */
    @Override
    public List<ColumnInfo> getColumnInfo(String tableName) {
        List<ColumnInfo> resultList = new ArrayList<>();
        String uniqueColumnName = findPrimaryKeyColumnName(tableName);
        if (StringUtils.isEmpty(uniqueColumnName)) {
            uniqueColumnName = findUniqueIndexColumnName(tableName);
        }
        String sql = String.format(SQL_MAP.get("QUERY_TABLE_COLUMNS"), tableName);
        try (Statement st = connection.createStatement()) {
            ResultSet rs = st.executeQuery(sql);
            if (rs == null) {
                return null;
            }
            while (rs.next()) {
                ColumnInfo col = new ColumnInfo();
                col.setTableName(rs.getString(1));
                col.setTableComment(rs.getString(2));
                col.setColumnName(rs.getString(3));
                col.setColumnComment(rs.getString(4));
                col.setColumnType(rs.getString(5));
                if (StringUtils.isNotEmpty(col.getColumnType())) {
                    col.setColumnType(col.getColumnType().toLowerCase());
                }
                col.setDefaultValue(rs.getString(10));
                if (col.getDefaultValue() != null) {
                    col.setDefaultValue(col.getDefaultValue().trim());
                    if ("null".equalsIgnoreCase(col.getDefaultValue()) || col.getDefaultValue().length() == 0) {
                        col.setDefaultValue(null);
                    }
                }
                if ("bigint".equalsIgnoreCase(col.getColumnType())
                        || "int".equalsIgnoreCase(col.getColumnType())
                        || "smallint".equalsIgnoreCase(col.getColumnType())
                        || "mediumint".equalsIgnoreCase(col.getColumnType())
                        || "tinyint".equalsIgnoreCase(col.getColumnType())
                        || "float".equalsIgnoreCase(col.getColumnType())
                        || "double".equalsIgnoreCase(col.getColumnType())
                        || "decimal".equalsIgnoreCase(col.getColumnType())) {
                    //数字类型
                    col.setIsNumber(GeneratorConst.YES);
                    col.setColumnPrecision(StringUtils.parseInt(rs.getString(6)));
                    col.setColumnScale(StringUtils.parseInt(rs.getString(7)));
                    col.setColumnLength(col.getColumnPrecision());
                } else if (StringUtils.isNotEmpty(col.getColumnType()) && (col.getColumnType().contains("char")
                        || col.getColumnType().contains("text"))) {
                    //字符型
                    col.setCharLength(StringUtils.parseInt(rs.getString(8)));
                    col.setColumnLength(col.getCharLength());
                    col.setIsChar(GeneratorConst.YES);
                } else {
                    col.setColumnLength(StringUtils.parseInt(rs.getString(9)));
                }
                col.setNullable("YES".equalsIgnoreCase(rs.getString(11)) ? GeneratorConst.YES : GeneratorConst.NO);
                if (StringUtils.isNotEmpty(uniqueColumnName) && uniqueColumnName.equalsIgnoreCase(col.getColumnName())) {
                    col.setIsPrimaryKey(GeneratorConst.YES);
                } else {
                    col.setIsPrimaryKey(GeneratorConst.NO);
                }
                resultList.add(col);
            }
            rs.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return resultList;
    }

    /**
     * 统一设置表名大小写
     *
     * @param t 原始表名
     * @return
     */
    @Override
    public String setTableNameCase(String t) {
        if (StringUtils.isEmpty(t)) {
            return t;
        }
        return t.toLowerCase();
    }

    /**
     * 获取主键字段名
     *
     * @param tableName 表名
     * @return
     */
    private String findPrimaryKeyColumnName(String tableName) {
        return selectOne(String.format(SQL_MAP.get("QUERY_PRIMARY_KEY").replaceAll("\n", ""), tableName));
    }

    /**
     * 获取唯一索引字段名
     *
     * @param tableName 表名
     * @return
     */
    private String findUniqueIndexColumnName(String tableName) {
        return selectOne(String.format(SQL_MAP.get("QUERY_UNIQUE_COLUMN"), tableName));
    }

    private String selectOne(String sql) {
        return this.selectLastOne(sql);
    }
}
