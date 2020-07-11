package com.foobar.generator.generator;

import com.foobar.generator.config.GeneratorConfig;
import com.foobar.generator.constant.GeneratorConst;
import com.foobar.generator.db.AbstractDbUtil;
import com.foobar.generator.db.MySQLUtil;
import com.foobar.generator.db.OracleUtil;
import com.foobar.generator.info.*;
import com.foobar.generator.util.StringUtils;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.apache.commons.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 数据表代码生成器
 *
 * @author yin
 */
public class TableCodeGenerator {
    private static final Logger logger = LoggerFactory.getLogger(TableCodeGenerator.class);

    /**
     * 数据库类型
     */
    private final String dbType;

    /**
     * 线程池
     */
    private final ExecutorService threadPool;

    /**
     * 模板缓存
     */
    private final Map<String, Template> TEMPLATE_MAP = new HashMap<>();

    /**
     * 数据库SCHEMA名称
     */
    private final String schemaName;

    /**
     * 模板配置
     */
    private final Configuration conf;

    /**
     * 所有表名
     */
    private final List<String> allTableNamesList;

    /**
     * 表字段缓存
     */
    private final Map<String, List<ColumnInfo>> columnsMap = new HashMap<>();

    /**
     * 当前用户名
     */
    private final String currentUser;

    /**
     * 需去除的表前缀
     */
    private String prefixToRemove;

    /**
     * java包名
     */
    private String pkgName;

    /**
     * 数据库操作工具
     */
    private final AbstractDbUtil dbUtil;

    /**
     * 是否生成所有
     */
    private boolean generateAll = true;

    /**
     * 基础输出路径
     */
    private String baseOutputPath;

    public void setGenerateAll(boolean generateAll) {
        this.generateAll = generateAll;
    }

    /**
     * 构造函数
     *
     * @param jdbcInfo JDBC参数
     */
    public TableCodeGenerator(JdbcInfo jdbcInfo) throws Exception {
        if (jdbcInfo == null) {
            throw new Exception("JDBC参数为null");
        }
        if (jdbcInfo.getDbType() == null) {
            throw new Exception("数据库类型为空");
        }
        dbType = jdbcInfo.getDbType().toLowerCase();
        if (GeneratorConst.MYSQL.equalsIgnoreCase(dbType)) {
            dbUtil = new MySQLUtil();
        } else if (GeneratorConst.ORACLE.equalsIgnoreCase(dbType)) {
            dbUtil = new OracleUtil();
        } else {
            throw new Exception("暂不支持该数据库类型");
        }
        try {
            dbUtil.init(jdbcInfo);
        } catch (Exception e) {
            logger.error("初始化数据库连接时发生异常", e);
            throw new Exception("初始化数据库连接时发生异常");
        }
        this.schemaName = jdbcInfo.getSchema();
        allTableNamesList = getAllTableNames(schemaName);
        if (allTableNamesList == null || allTableNamesList.isEmpty()) {
            throw new Exception("该数据库没有表");
        }
        logger.info("数据库中有 {} 张表", allTableNamesList.size());
        //初始化模板
        conf = new Configuration(Configuration.VERSION_2_3_28);
        conf.setClassForTemplateLoading(this.getClass(), "/");

        //获取当前用户名
        currentUser = System.getenv().get("USERNAME");

        int cpus = Runtime.getRuntime().availableProcessors();
        //最多4个线程
        threadPool = Executors.newFixedThreadPool(Math.min(cpus, 4));
    }

    /**
     * 生成文件
     *
     * @param outputPath     输出路径
     * @param tableNames     表名(多个以逗号隔开,留空为全部)
     * @param pkgName        java包名
     * @param prefixToRemove 需去掉的表名前缀
     */
    public void run(String outputPath, String tableNames, String pkgName, String prefixToRemove) throws Exception {
        long begin = System.currentTimeMillis();
        checkDir(outputPath);
        this.pkgName = pkgName;
        this.prefixToRemove = prefixToRemove;
        List<String> tableNamesToSubmit = findTableNamesToSubmit(tableNames);
        logger.info("本次将生成 {} 张表的代码", tableNamesToSubmit.size());
        prepareColumnsCache(tableNamesToSubmit);
        dbUtil.clean();

        tableNamesToSubmit.forEach(t -> threadPool.execute(() -> generateTableCodeFiles(t)));
        threadPool.shutdown();
        while (!threadPool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            logger.debug("等待线程池关闭");
        }
        logger.info("{}下面表相应代码已生成到 {}, 耗时 {} 毫秒, 总计 {} 张表", this.schemaName, outputPath, System.currentTimeMillis() - begin, tableNamesToSubmit.size());
    }

    /**
     * 获取所有表名
     *
     * @param schemaName SCHEMA名称
     * @return SCHEMA下面所有表名
     */
    private List<String> getAllTableNames(String schemaName) {
        return this.dbUtil.getAllTableNames(schemaName);
    }

    /**
     * 获取字段信息
     *
     * @param tableName 表名
     * @return 表所有字段
     */
    private List<ColumnInfo> getColumnInfo(String tableName) {
        List<ColumnInfo> resultList = dbUtil.getColumnInfo(tableName);
        if (resultList != null && !resultList.isEmpty()) {
            resultList.forEach(c -> {
                if (c == null) {
                    return;
                }
                c.setColumnCamelNameLower(StringUtils.underlineToCamel(c.getColumnName(), false));
                c.setColumnCamelNameUpper(StringUtils.underlineToCamel(c.getColumnName(), true));
                c.setColumnJavaType(GeneratorConst.javaBoxTypeMap.get(c.getColumnType().toLowerCase()));
                if (StringUtils.isEmpty(c.getColumnJavaType())) {
                    throw new RuntimeException("数据库字段类型 " + c.getColumnType() + " 无法映射到Java类型");
                }
                c.setColumnMyBatisType(GeneratorConst.mybatisTypeMap.get(c.getColumnType().toLowerCase()));
                if (StringUtils.isEmpty(c.getColumnMyBatisType())) {
                    throw new RuntimeException("数据库字段类型 " + c.getColumnType() + " 无法映射到MyBatis JdbcType");
                }
                if (c.getIsNumber() == 1) {
                    if (c.getColumnScale() > 0) {
                        //有小数的时候：Java类中统一使用BigDecimal类型，MybatisXML中jdbcType统一使用DECIMAL类型
                        c.setColumnJavaType("BigDecimal");
                        c.setColumnMyBatisType("DECIMAL");
                    }
                }
                if (StringUtils.isEmpty(c.getColumnComment())) {
                    c.setColumnComment(c.getColumnName());
                }
            });
            logger.info("数据表 {} 包含 {} 个字段", tableName, resultList.size());
        }
        return resultList;
    }

    /**
     * 检查目录
     *
     * @param outputPath 输出路径
     */
    private void checkDir(String outputPath) {
        if (outputPath == null) {
            throw new IllegalArgumentException("输出路径为空");
        }
        if (!outputPath.endsWith(File.separator)) {
            outputPath += File.separator;
        }
        File outputDir = new File(outputPath);
        if (outputDir.exists()) {
            if (!outputDir.isDirectory()) {
                throw new RuntimeException("路径" + outputPath + "不是一个目录");
            }
        } else {
            if (!outputDir.mkdirs()) {
                throw new RuntimeException("创建目录" + outputPath + "失败");
            }
        }
        this.baseOutputPath = outputPath;
        //初始化各个目录
        GeneratorConfig.coreTemplateList.forEach(t -> checkSubDir(t.getTargetPkgName()));
        if (this.generateAll) {
            GeneratorConfig.otherTemplateList.forEach(t -> checkSubDir(t.getTargetPkgName()));
        }
    }

    /**
     * 检查子目录
     *
     * @param subDir 子目录名称
     */
    private void checkSubDir(String subDir) {
        if (StringUtils.isEmpty(subDir)) {
            return;
        }
        String realPath = this.baseOutputPath + File.separator + subDir;
        Path path = Paths.get(realPath);
        if (!path.toFile().exists()) {
            try {
                Files.createDirectory(path);
            } catch (IOException e) {
                logger.error("无法创建目录{}", realPath, e);
            }
        }
    }

    /**
     * 返回需要处理的表名
     *
     * @param tableNames 操作者传入的表名
     * @return 需要处理的表名
     */
    private List<String> findTableNamesToSubmit(String tableNames) {
        if (StringUtils.isEmpty(tableNames)) {
            return allTableNamesList;
        }
        tableNames = dbUtil.setTableNameCase(tableNames);
        List<String> tableNamesToSubmit = new ArrayList<>();
        String[] tmp = tableNames.split(",");
        if (tmp.length > 0) {
            for (String t : tmp) {
                if (StringUtils.isNotEmpty(t) && allTableNamesList.contains(t)) {
                    tableNamesToSubmit.add(t);
                }
            }
        }
        return tableNamesToSubmit;
    }

    /**
     * 准备好字段信息缓存
     *
     * @param tableNamesToSubmit 待处理的表名
     */
    private void prepareColumnsCache(List<String> tableNamesToSubmit) {
        if (tableNamesToSubmit == null || tableNamesToSubmit.isEmpty()) {
            return;
        }
        tableNamesToSubmit.forEach(t -> {
            List<ColumnInfo> columnInfoList = getColumnInfo(t);
            if (columnInfoList == null || columnInfoList.isEmpty()) {
                return;
            }
            columnsMap.put(t, columnInfoList);
        });
    }

    /**
     * 生成数据表的所有代码文件
     *
     * @param tableName 表名
     */
    private void generateTableCodeFiles(String tableName) {
        if (StringUtils.isEmpty(tableName)) {
            return;
        }
        List<ColumnInfo> columnInfoList = columnsMap.get(tableName);
        if (columnInfoList == null || columnInfoList.isEmpty()) {
            logger.warn("数据表 {} 无字段, 跳过!", tableName);
            return;
        }
        String simpleTableName = tableName;
        if (StringUtils.isNotEmpty(prefixToRemove) && tableName.startsWith(prefixToRemove)) {
            //去掉前缀后的表名
            simpleTableName = StringUtils.removeStart(tableName, prefixToRemove);
        }
        String javaClassName = StringUtils.underlineToCamel(simpleTableName, true);

        //表基本信息
        TableInfo tableInfo = new TableInfo();
        tableInfo.setDbType(dbType);
        //表名
        tableInfo.setName(tableName);
        //表注释
        tableInfo.setComments(columnInfoList.get(0).getTableComment());
        //所有字段
        tableInfo.setColumns(columnInfoList);
        //java类名
        tableInfo.setJavaClassName(javaClassName);
        //java类名(首字母小写)
        tableInfo.setJavaClassNameLower(WordUtils.uncapitalize(javaClassName));

        //others
        tableInfo.setImports(generateImports(columnInfoList));
        tableInfo.setAuthor(currentUser);

        RenderData data = new RenderData();
        data.setBasePkgName(StringUtils.isEmpty(pkgName) ? javaClassName.toLowerCase() : StringUtils.trim(pkgName));
        data.setTable(tableInfo);
        data.setUuid((list) -> UUID.randomUUID());

        //输出
        render(GeneratorConfig.coreTemplateList, data, javaClassName);
        if (this.generateAll) {
            render(GeneratorConfig.otherTemplateList, data, javaClassName);
        }
        logger.info("数据表 {} 的代码已生成完毕", tableName);
    }

    /**
     * 渲染
     *
     * @param templateInfoList
     * @param data
     * @param javaClassName
     */
    private void render(List<TemplateInfo> templateInfoList, RenderData data, String javaClassName) {
        if (templateInfoList == null || templateInfoList.isEmpty()) {
            return;
        }

        for (TemplateInfo ti : templateInfoList) {
            if (ti == null) {
                continue;
            }
            File dir = new File(baseOutputPath + File.separator + ti.getTargetPkgName());
            if (!dir.isDirectory()) {
                throw new RuntimeException("路径" + dir.getAbsolutePath() + "不是目录");
            }
            String out = dir.getAbsolutePath() + File.separator + ti.getTargetFileName().replace(GeneratorConst.PLACEHOLDER, javaClassName);
            data.getTable().setPkgName(data.getBasePkgName() + "." + ti.getTargetPkgName());
            renderFile(getTemplate("tpl/" + ti.getTemplateName()), data, out);
        }
    }

    /**
     * 渲染文件
     *
     * @param tpl     模板
     * @param data    数据
     * @param outPath 输出路径
     * @throws Exception
     */
    private void renderFile(Template tpl, RenderData data, String outPath) {
        if (tpl == null || data == null || StringUtils.isEmpty(outPath)) {
            return;
        }
        Writer out;
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outPath)));
            tpl.process(data, out);
            logger.info("已生成代码文件 {}", outPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 生成Java类中import内容
     *
     * @param columnInfoList 字段信息列表
     * @return
     */
    private SortedSet<String> generateImports(List<ColumnInfo> columnInfoList) {
        SortedSet<String> imports = new TreeSet<>();
        if (columnInfoList == null || columnInfoList.isEmpty()) {
            return imports;
        }
        columnInfoList.forEach(c -> {
            if (c == null) {
                return;
            }
            String importStr = GeneratorConst.importsTypeMap.get(c.getColumnJavaType());
            if (StringUtils.isNotEmpty(importStr)) {
                imports.add(importStr);
            }
        });
        return imports;
    }

    /**
     * 获取模板
     *
     * @param name 模板文件名
     * @return
     */
    private Template getTemplate(String name) {
        return TEMPLATE_MAP.computeIfAbsent(name, k -> {
            try {
                return conf.getTemplate(k);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        });
    }
}
