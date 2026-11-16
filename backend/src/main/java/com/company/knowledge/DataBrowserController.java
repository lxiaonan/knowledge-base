package com.company.knowledge;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Read-only, explicit projections. Neither table names nor SQL fragments come from the client. */
@RestController
@RequestMapping("/api/admin/data")
@Validated
public class DataBrowserController {
    public record Column(String key,String label,String kind) {}
    public record Option(String value,String label) {}
    public record Table(String name,String label,String description,long count,List<Column> columns,
                        boolean knowledgeBaseFilter,List<Option> states) {}
    public record Overview(String database,String version,String catalog,boolean vectorEnabled,String embeddingModel,
                           long indexedChunks,List<Table> tables,List<Option> knowledgeBases) {}
    public record Row(String key,Map<String,Object> values) {}
    public record Page(String table,int page,int pageSize,long total,List<Row> rows) {}
    public record Vector(int dimensions,List<Double> values) {}
    public record Detail(String table,Map<String,Object> values,Vector vector) {}
    private record Definition(String name,String label,String description,String from,String projection,String detailProjection,
                              String order,List<String> primaryKeys,List<Column> columns,List<String> search,
                              String knowledgeBase,String state,List<Option> states) {}
    private final Repository repo;
    private final Access access;
    private final AppProperties config;
    private final DataSource datasource;
    private final ObjectMapper json;
    private final Map<String,Definition> definitions;
    public DataBrowserController(Repository repo,Access access,AppProperties config,DataSource datasource,ObjectMapper json) {
        this.repo=repo;this.access=access;this.config=config;this.datasource=datasource;this.json=json;
        var tables=new LinkedHashMap<String,Definition>();
        String dimensions=config.demo()?"NULL":"vector_dims(c.embedding)";
        String indexed=config.demo()?"FALSE":"c.embedding IS NOT NULL";
        define(tables,new Definition("document","文档","文件信息、解析状态与知识片段数量", "document d JOIN knowledge_base k ON k.id=d.knowledge_base_id",
            "d.id,d.name,d.knowledge_base_id,k.name knowledge_base_name,d.status,d.size_bytes,d.chunk_count,d.created_at",
            "d.id,d.name,d.knowledge_base_id,k.name knowledge_base_name,d.status,d.size_bytes,d.chunk_count,d.created_at,d.content_type,d.file_key,d.error_message",
            "d.created_at DESC,d.id",List.of("id"),List.of(col("name","文档名称","text"),col("knowledge_base_name","知识库","text"),col("status","处理状态","status"),col("chunk_count","片段数","number"),col("size_bytes","文件大小","bytes"),col("created_at","上传时间","date"),col("id","文档 ID","id")),
            List.of("d.name","d.id","k.name"),"d.knowledge_base_id","d.status",List.of(opt("READY","可检索"),opt("PROCESSING","解析中"),opt("QUEUED","排队中"),opt("FAILED","处理失败"))));
        String chunkFields="c.id,c.document_id,d.name document_name,d.knowledge_base_id,k.name knowledge_base_name,c.ordinal,c.page_number,"+dimensions+" embedding_dimensions,"+indexed+" indexed";
        define(tables,new Definition("document_chunk","知识片段与向量","切分后的文档原文及本地模型生成的向量", "document_chunk c JOIN document d ON d.id=c.document_id JOIN knowledge_base k ON k.id=d.knowledge_base_id",
            chunkFields+",LEFT(c.content,160) content_preview,LENGTH(c.content) content_length",
            chunkFields+",c.content"+(config.demo()?"":",CAST(c.embedding AS VARCHAR) embedding"),
            "d.created_at DESC,d.id,c.ordinal",List.of("id"),List.of(col("document_name","所属文档","text"),col("ordinal","片段序号","number"),col("content_preview","原文摘要","preview"),col("embedding_dimensions","向量维度","number"),col("indexed","向量状态","boolean"),col("id","片段 ID","id")),
            List.of("c.content","c.id","d.name","k.name"),"d.knowledge_base_id","CASE WHEN "+indexed+" THEN 'INDEXED' ELSE 'PENDING' END",List.of(opt("INDEXED","已有向量"),opt("PENDING","未生成向量"))));
        define(tables,new Definition("knowledge_base","知识库","知识库描述、所有者与创建时间","knowledge_base k JOIN app_user u ON u.id=k.owner_id",
            "k.id,k.name,k.description,k.owner_id,u.display_name owner_name,k.created_at",null,
            "k.created_at DESC,k.id",List.of("id"),List.of(col("name","知识库名称","text"),col("description","简介","preview"),col("owner_name","所有者","text"),col("created_at","创建时间","date"),col("id","知识库 ID","id")),
            List.of("k.name","k.description","k.id","u.display_name"),"k.id",null,List.of()));
        define(tables,new Definition("conversation","对话","所有用户的对话归属与标题","conversation v JOIN knowledge_base k ON k.id=v.knowledge_base_id JOIN app_user u ON u.id=v.user_id",
            "v.id,v.title,v.knowledge_base_id,k.name knowledge_base_name,v.user_id,u.display_name user_name,v.created_at",null,
            "v.created_at DESC,v.id",List.of("id"),List.of(col("title","对话标题","text"),col("knowledge_base_name","知识库","text"),col("user_name","用户","text"),col("created_at","创建时间","date"),col("id","对话 ID","id")),
            List.of("v.title","v.id","u.display_name","k.name"),"v.knowledge_base_id",null,List.of()));
        String messageFrom="chat_message m JOIN conversation v ON v.id=m.conversation_id JOIN knowledge_base k ON k.id=v.knowledge_base_id JOIN app_user u ON u.id=v.user_id";
        String messageFields="m.id,m.conversation_id,v.title conversation_title,v.knowledge_base_id,k.name knowledge_base_name,u.display_name user_name,m.role,m.created_at";
        define(tables,new Definition("chat_message","聊天消息","提问、回答与引用快照",""+messageFrom,
            messageFields+",LEFT(m.content,160) content_preview",messageFields+",m.content,m.sources_json",
            "m.created_at DESC,m.id",List.of("id"),List.of(col("role","角色","status"),col("content_preview","消息内容","preview"),col("conversation_title","所属对话","text"),col("user_name","用户","text"),col("created_at","时间","date"),col("id","消息 ID","id")),
            List.of("m.content","m.id","v.title","u.display_name"),"v.knowledge_base_id","m.role",List.of(opt("user","用户"),opt("assistant","助手"))));
        define(tables,new Definition("app_user","用户","用户基本信息；不返回密码字段","app_user u",
            "u.id,u.username,u.display_name,u.role,u.created_at",null,
            "u.created_at DESC,u.id",List.of("id"),List.of(col("username","账号","text"),col("display_name","显示名称","text"),col("role","角色","status"),col("created_at","创建时间","date"),col("id","用户 ID","id")),
            List.of("u.username","u.display_name","u.id"),null,"u.role",List.of(opt("ADMIN","管理员"),opt("MEMBER","成员"))));
        define(tables,new Definition("knowledge_member","成员授权","知识库与成员之间的访问授权","knowledge_member m JOIN knowledge_base k ON k.id=m.knowledge_base_id JOIN app_user u ON u.id=m.user_id",
            "m.knowledge_base_id,k.name knowledge_base_name,m.user_id,u.username,u.display_name user_name",null,
            "k.name,u.username,m.knowledge_base_id,m.user_id",List.of("knowledge_base_id","user_id"),List.of(col("knowledge_base_name","知识库","text"),col("username","成员账号","text"),col("user_name","成员名称","text"),col("knowledge_base_id","知识库 ID","id"),col("user_id","用户 ID","id")),
            List.of("k.name","u.username","u.display_name","m.knowledge_base_id","m.user_id"),"m.knowledge_base_id",null,List.of()));
        define(tables,new Definition("app_setting","系统参数","仅展示参数名称及保存状态，参数值隐藏","app_setting s",
            "s.setting_key,CASE WHEN s.setting_key='chat_config_encrypted' THEN '加密保存，内容隐藏' ELSE '系统参数，内容隐藏' END value_status",null,
            "s.setting_key",List.of("setting_key"),List.of(col("setting_key","参数名称","text"),col("value_status","保存状态","text")),
            List.of("s.setting_key"),null,null,List.of()));
        definitions=Collections.unmodifiableMap(tables);
    }
    private static Column col(String key,String label,String kind){return new Column(key,label,kind);}
    private static Option opt(String value,String label){return new Option(value,label);}
    private static void define(Map<String,Definition> map,Definition definition){map.put(definition.name(),definition);}
    private void admin(){if(!"ADMIN".equals(access.current().role()))throw ApiException.forbidden();}
    private Definition definition(String name){var value=definitions.get(name);if(value==null)throw ApiException.missing();return value;}
    @GetMapping("/overview") public Overview overview() throws SQLException {
        admin();
        List<Table> tables=new ArrayList<>();
        for(var def:definitions.values()) {
            long count=repo.db().sql("SELECT COUNT(*) FROM "+def.from()).query(Long.class).single();
            tables.add(new Table(def.name(),def.label(),def.description(),count,def.columns(),def.knowledgeBase()!=null,def.states()));
        }
        long vectors=config.demo()?0:repo.db().sql("SELECT COUNT(*) FROM document_chunk WHERE embedding IS NOT NULL").query(Long.class).single();
        var bases=repo.db().sql("SELECT id,name FROM knowledge_base ORDER BY name,id").query((r,i)->opt(r.getString("id"),r.getString("name"))).list();
        try(var connection=datasource.getConnection()) {
            var metadata=connection.getMetaData();
            return new Overview(metadata.getDatabaseProductName(),metadata.getDatabaseProductVersion(),connection.getCatalog(),!config.demo(),config.ai().embeddingModel(),vectors,tables,bases);
        }
    }
    @GetMapping("/tables/{table}") public Page rows(@PathVariable String table,
            @RequestParam(defaultValue="1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int pageSize,
            @RequestParam(defaultValue="") @Size(max=200) String q,
            @RequestParam(defaultValue="") @Size(max=36) String knowledgeBaseId,
            @RequestParam(defaultValue="") @Size(max=32) String state) {
        admin();var def=definition(table);
        StringBuilder where=new StringBuilder(" WHERE 1=1");Map<String,Object> parameters=new HashMap<>();
        if(!q.isBlank()) {
            String pattern="%"+q.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            where.append(" AND (").append(def.search().stream().map(field->"LOWER("+field+") LIKE :q ESCAPE '!' ").collect(java.util.stream.Collectors.joining(" OR "))).append(")");
            parameters.put("q",pattern);
        }
        if(!knowledgeBaseId.isBlank()) {
            if(def.knowledgeBase()==null)throw ApiException.bad("此数据表不支持知识库筛选");
            where.append(" AND ").append(def.knowledgeBase()).append("=:kb");parameters.put("kb",knowledgeBaseId);
        }
        if(!state.isBlank()) {
            if(def.state()==null || def.states().stream().noneMatch(option->option.value().equals(state)))throw ApiException.bad("筛选状态无效");
            where.append(" AND ").append(def.state()).append("=:state");parameters.put("state",state);
        }
        long total=repo.db().sql("SELECT COUNT(*) FROM "+def.from()+where).params(parameters).query(Long.class).single();
        int effective=(int)Math.min(page,Math.max(1,(total+pageSize-1)/pageSize));
        parameters.put("limit",pageSize);parameters.put("offset",((long)effective-1)*pageSize);
        var records=repo.db().sql("SELECT "+def.projection()+" FROM "+def.from()+where+" ORDER BY "+def.order()+" LIMIT :limit OFFSET :offset")
            .params(parameters).query((r,i)->values(r)).list().stream().map(value->new Row(encode(def,value),value)).toList();
        return new Page(table,effective,pageSize,total,records);
    }
    @GetMapping("/tables/{table}/rows/{key}") public Detail detail(@PathVariable String table,@PathVariable @Size(max=512) String key) {
        admin();var def=definition(table);var keys=decode(key,def.primaryKeys().size());
        Map<String,Object> params=new HashMap<>();List<String> conditions=new ArrayList<>();
        // Each primary-key expression is selected from the fixed projection, not supplied by the client.
        for(int i=0;i<keys.size();i++) {
            String column=def.primaryKeys().get(i);
            String alias=switch(table){case "document"->"d";case "document_chunk"->"c";case "knowledge_base"->"k";case "conversation"->"v";case "chat_message","knowledge_member"->"m";case "app_user"->"u";default->"s";};
            conditions.add(alias+"."+column+"=:key"+i);params.put("key"+i,keys.get(i));
        }
        String projection=def.detailProjection()==null?def.projection():def.detailProjection();
        var row=repo.db().sql("SELECT "+projection+" FROM "+def.from()+" WHERE "+String.join(" AND ",conditions)).params(params)
            .query((r,i)->values(r)).optional().orElseThrow(ApiException::missing);
        Vector vector=null;Object raw=row.remove("embedding");
        if(raw instanceof String text) {
            try {List<Double> values=json.readValue(text,new TypeReference<>(){});vector=new Vector(values.size(),values);}
            catch(Exception e){throw new IllegalStateException("Invalid stored vector",e);}
        }
        return new Detail(table,row,vector);
    }
    private Map<String,Object> values(ResultSet result) throws SQLException {
        Map<String,Object> values=new LinkedHashMap<>();var metadata=result.getMetaData();
        for(int i=1;i<=metadata.getColumnCount();i++) {
            Object value=result.getObject(i);
            if(value instanceof java.sql.Timestamp timestamp)value=timestamp.toInstant().toString();
            if(value instanceof OffsetDateTime timestamp)value=timestamp.toInstant().toString();
            values.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT),value);
        }
        return values;
    }
    private String encode(Definition def,Map<String,Object> values) {
        try {return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(def.primaryKeys().stream().map(key->String.valueOf(values.get(key))).toList()));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private List<String> decode(String key,int size) {
        try {
            List<String> keys=json.readValue(new String(Base64.getUrlDecoder().decode(key),StandardCharsets.UTF_8),new TypeReference<>(){});
            if(keys.size()!=size || keys.stream().anyMatch(value->value==null || value.isBlank() || value.length()>128))throw new IllegalArgumentException();
            return keys;
        } catch(Exception e){throw ApiException.bad("记录标识无效");}
    }
}
