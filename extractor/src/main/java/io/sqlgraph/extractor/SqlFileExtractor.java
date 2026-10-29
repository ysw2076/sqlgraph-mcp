package io.sqlgraph.extractor;

import io.sqlgraph.core.SqlScripts;
import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.SourceLocation;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.spi.SqlExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * .sql 파일의 DML/SELECT/CALL 문을 추출한다.
 * DDL(CREATE/ALTER/DROP 등)과 SET, COMMIT 같은 문장은 건너뛴다 — DDL 은 DdlSchemaSource 담당.
 */
public final class SqlFileExtractor implements SqlExtractor {

    @Override
    public String name() {
        return "sql-file";
    }

    @Override
    public boolean supports(String relativePath) {
        return relativePath.toLowerCase(Locale.ROOT).endsWith(".sql");
    }

    @Override
    public List<ExtractedSql> extract(String relativePath, String content) {
        List<ExtractedSql> out = new ArrayList<>();
        int seq = 0;
        for (SqlScripts.Chunk c : SqlScripts.split(content)) {
            SqlKind kind = SqlKind.guess(c.sql());
            if (kind == SqlKind.DDL || kind == SqlKind.OTHER) continue;
            seq++;
            out.add(new ExtractedSql(c.sql().replaceAll("\\s+", " ").strip(), relativePath + "#" + seq, kind,
                    new SourceLocation(relativePath, c.line()), c.sql().contains("${") || c.sql().matches("(?s).*&&?[A-Za-z_].*")));
        }
        return out;
    }
}
