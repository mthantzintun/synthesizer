package org.thesis.research.litreview.config.synthesis;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>The one thing that <em>must</em> be configured here is the pgvector JDBC
 * type. Without it the driver has no idea how to serialise a {@code float[]}
 * into a {@code vector} parameter and every embedding write fails.
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    /**
     * {@code NamedParameterJdbcTemplate} for the embedding and hybrid-search
     * statements, which need named parameters far more than positional ones
     * (the RRF query binds the same filter set into two CTEs).
     */
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Registers the pgvector types on every pooled connection.
     *
     * <p>Uses Hikari's {@code connectionInitSql} rather than
     * {@code PGvector.addVectorType(connection)} on a single borrowed
     * connection: Hikari hands out long-lived connections, and a type
     * registered on only one of them is invisible to the rest of the pool.
     * The SQL below is what {@code PGvector.registerTypes} itself issues.
     *
     * <p>{@code ADD TYPE} is idempotent, so re-running it per connection is
     * safe. It requires the {@code vector} extension, which the Flyway
     * migration creates.
     */
    @Bean
    PgVectorTypeInitializer pgVectorTypeInitializer(DataSource dataSource) {
        String pgvector = "ADD TYPE vector AS com.pgvector.PGvector";
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            String existing = hikari.getConnectionInitSql();
            if (existing == null || !existing.contains("PGvector")) {
                hikari.setConnectionInitSql(existing == null ? pgvector : existing + "; " + pgvector);
                log.info("Registered the pgvector JDBC type on the Hikari connection pool");
            }
        }
        else {
            // Non-Hikari pool (tests, or a future datasource swap): register on
            // a single connection as a best effort. Hikari is the default in
            // this application, so this branch is a safety net, not the norm.
            try (java.sql.Connection connection = dataSource.getConnection()) {
                com.pgvector.PGvector.addVectorType(connection);
                log.info("Registered the pgvector JDBC type on a single connection ({})",
                        dataSource.getClass().getSimpleName());
            }
            catch (java.sql.SQLException e) {
                log.warn("Could not register the pgvector JDBC type up front; embedding writes "
                        + "will fail until the database is reachable: {}", e.getMessage());
            }
        }
        return new PgVectorTypeInitializer();
    }

    /**
     * Marker bean that exists purely to force the registration above to run at
     * startup rather than lazily on the first embedding write.
     */
    public static final class PgVectorTypeInitializer {
    }
}
