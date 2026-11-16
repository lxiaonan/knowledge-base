package com.company.knowledge;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit, one-time local migration. Original H2 files are opened read-only and never changed. */
@Component
@Order(-100)
public class DemoImport implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(DemoImport.class);
    private final Repository repo;
    private final TransactionTemplate tx;
    private final AppProperties config;
    private final String source;
    public DemoImport(Repository repo,TransactionTemplate tx,AppProperties config,
                      @Value("${app.import-demo-path:}") String source) {
        this.repo=repo;this.tx=tx;this.config=config;this.source=source;
    }
    @Override public void run(ApplicationArguments args) {
        if (config.demo() || source.isBlank()) return;
        if (repo.db().sql("SELECT COUNT(*) FROM app_setting WHERE setting_key='demo_import_complete'").query(Long.class).single()>0) return;
        if (repo.db().sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()>0)
            throw new IllegalStateException("Demo import requires an empty destination. Existing data was not changed.");
        String path=Path.of(source).toAbsolutePath().normalize().toString().replace('\\','/');
        if (path.contains(";")) throw new IllegalArgumentException("Invalid import path");
        tx.executeWithoutResult(transaction -> {
            try(var connection=DriverManager.getConnection("jdbc:h2:file:"+path+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE","sa","")) {
                for (String table:List.of("app_user","knowledge_base","knowledge_member","document","document_chunk","conversation","chat_message")) {
                    int count=0;
                    try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT * FROM "+table)) {
                        var meta=rows.getMetaData();int columns=meta.getColumnCount();
                        String names=java.util.stream.IntStream.rangeClosed(1,columns).mapToObj(i -> {
                            try{return meta.getColumnName(i);}catch(SQLException e){throw new IllegalStateException(e);}
                        }).collect(java.util.stream.Collectors.joining(","));
                        String placeholders=String.join(",",java.util.Collections.nCopies(columns,"?"));
                        while(rows.next()) {
                            var insert=repo.db().sql("INSERT INTO "+table+"("+names+") VALUES ("+placeholders+")");
                            for(int i=1;i<=columns;i++) insert.param(i,rows.getObject(i));
                            insert.update();count++;
                        }
                    }
                    log.info("Imported {} rows into {}",count,table);
                }
                repo.db().sql("UPDATE document SET status='QUEUED',error_message=NULL,chunk_count=0").update();
                repo.db().sql("INSERT INTO app_setting(setting_key,setting_value) VALUES ('demo_import_complete','true')").update();
            } catch(SQLException e) {throw new IllegalStateException("Demo import failed; destination transaction rolled back",e);}
        });
    }
}
