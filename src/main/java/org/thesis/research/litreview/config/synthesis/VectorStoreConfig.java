package org.thesis.research.litreview.config.synthesis;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Storage wiring for the custom hybrid {@code chunk} table.
 *
 * <p>Spring AI's {@code PgVectorStore} is deliberately not used and its
 * autoconfiguration is excluded in application.yaml. That store owns its own
 * table with its own schema and only knows how to do vector similarity; this
 * project needs cosine distance <em>and</em> full-text rank fused in one SQL
 * statement against a table that also carries section, page and canonical
 * labels. Owning the table (see {@code V1__init_schema.sql}) is the simpler
 * trade. {@code ChunkJdbcRepository} is what actually talks to it.
 *
 * <p>The pgvector JDBC type is registered declaratively in application.yaml
 * ({@code spring.datasource.hikari.connection-init-sql}) rather than here -
 * see the note at the bottom of this class for why that has to be the case.
 */
@Configuration
public class VectorStoreConfig {

    /**
     * {@code NamedParameterJdbcTemplate} for the embedding and hybrid-search
     * statements, which need named parameters far more than positional ones
     * (the RRF query binds the same filter set into two CTEs).
     */
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    // Note: there is deliberately no bean here registering the pgvector JDBC
    // type. That registration lives in application.yaml as
    // spring.datasource.hikari.connection-init-sql, because it has to happen
    // while the connection pool is being built. Doing it from a bean - via
    // HikariDataSource.setConnectionInitSql - fails at startup with
    // "The configuration of the pool is sealed once started", since Spring Boot
    // has already started the pool by the time a @Bean method runs.
}
