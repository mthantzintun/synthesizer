# Synthesizer

A literature review synthesis pipeline which will read a BibTeX library and map with PDFs, parses the full text, and produces the cross-paper artefacts a
literature chapter needs, such as a concept matrix, contradictions, consensus, a coverage grid that makes gaps visible, and any gap you
propose.

Built as a single researcher tool that runs locally, it is honest about what it
could not find, and it never invents a citation.

## Pipeline

| Phase | What it does                                                                    | Code |
| --- |---------------------------------------------------------------------------------| --- |
| 1 | Parse `library.bib`, resolve EndNote attachment paths to real PDFs, validate    | `ingest/bibtex` |
| 2 | Parse each PDF with GROBID into TEI, cache by SHA-256                           | `venue/grobid` |
| 3 | Chunk section aware, embed locally (ONNX), store vector and tsvector in one row | `service/ChunkingService`, `entity/ChunkJdbcRepository` |
| 4 | Extract a structured concept matrix per paper                                   | `service/extraction` |
| 5 | Find contradictions, mine future-work consensus, build the gap grid             | `service/synthesis` |
| 6 | Attack a proposed gap with corpus-grounded evidence                             | `service/paper/GapSteelmanService` |

## Requirements

- Java 21
- Docker (for Postgres + GROBID)
- An OpenRouter API key (only for phases 4-6)

## Configuration and secrets

All credentials live in `.env`, which is gitignored:

```bash
cp .env.example .env
# then fill in OPENROUTER_API_KEY
```

`.env.example` is committed and documents every variable; never put a real
secret in it.

The application reads `.env` itself, via `DotenvEnvironmentPostProcessor`
(registered in `src/main/resources/META-INF/spring.factories`). Spring Boot has
no native `.env` support - it is a Compose and shell convention - so without
that class the file would configure the Postgres and GROBID containers but be
ignored by the app, and the two halves of the stack would silently disagree
about credentials.

Precedence, highest first:

1. real environment variables and `-D` flags
2. `.env`
3. `application.yaml` defaults

So a real env var always wins, which is what you want in CI:

```bash
OPENROUTER_API_KEY=sk-... ./mvnw spring-boot:run   # beats .env
```

On startup the app logs what it loaded, so there is no guessing:

```
DotenvEnvironmentPostProcessor : Loaded 8 properties from ...\.env
                                (real environment variables take precedence)
```

Every default in `application.yaml` matches `docker-compose.yml`, so a fresh
checkout runs with no `.env` at all. Point `DOTENV_PATH` at a file elsewhere to
override the search.

## Running

Two ways. Both read `.env` for credentials and the LLM key.

**Host loop (default).** Infra in Docker, app on the host — the fast edit cycle:

```bash
docker compose up -d            # postgres, grobid, pgadmin
./mvnw spring-boot:run
```

**Whole stack in Docker.** For demos and reproducibility:

```bash
docker compose --profile app up -d --build
```

The `app` service is behind a profile deliberately: rebuilding the image takes
minutes versus seconds for `spring-boot:run`, and the infra containers change
almost never.

### Where configuration lives

Three places, split by concern. Keeping the split strict is what prevents the
same variable being set for one environment and forgotten for another:

| Concern | Lives in | Examples |
| --- | --- | --- |
| Image filesystem | `Dockerfile` `ENV` | `LITREVIEW_PDF_ROOT`, `LITREVIEW_BIB_FILE` |
| Network topology | compose `environment:` | `LITREVIEW_DB_URL`, `LITREVIEW_GROBID_URL` |
| Secrets | `.env` (gitignored) | `OPENROUTER_API_KEY`, `POSTGRES_PASSWORD` |

`localhost` means different things on the host and in a container, so the DB and
GROBID URLs are **never** in `.env` — that file holds host-oriented values.
Compose's `environment:` overrides `env_file:`, which is what makes the
containerised run reach `postgres:5432` instead of its own `localhost`.

GROBID takes ~60s to load its models on a cold start. The `app` service waits on
both dependencies' healthchecks (`condition: service_healthy`), so it will not
start early and fail with a connection error that looks like a misconfigured URL.

## Endpoints

```bash
# 1-3: ingest, chunk, embed
curl -X POST "localhost:8080/api/ingest/run?force=false"
curl       "localhost:8080/api/ingest/status"
curl       "localhost:8080/api/ingest/failures"
curl -X POST "localhost:8080/api/ingest/RN63/reingest"

# retrieval
curl -X POST localhost:8080/api/search \
     -H 'Content-Type: application/json' \
     -d '{"query":"last-mile emissions","topK":10}'

# 4: concept matrix
curl -X POST localhost:8080/api/extraction/run
curl       localhost:8080/api/extraction/RN63
curl       "localhost:8080/api/extraction?construct=visibility"

# 5-6: synthesis
curl -X POST localhost:8080/api/synthesis/contradictions/run
curl       localhost:8080/api/synthesis/contradictions/unreviewed
curl -X POST localhost:8080/api/synthesis/contradictions/1/review \
     -H 'Content-Type: application/json' -d '{"verdict":"real gap"}'
curl -X POST localhost:8080/api/synthesis/future-work/run
curl       localhost:8080/api/synthesis/gap-grid/asserted-but-untested
curl -X POST localhost:8080/api/synthesis/steelman \
     -H 'Content-Type: application/json' \
     -d '{"gap":"no study measures parcel locker effects on emissions in dense EU cities"}'
```

## Design notes

A few decisions are worth keeping permanently.

** `spring.ai.model.embedding`: transformers must stay set.** Both the OpenAI and
the transformers embedding auto configurations are `@ConditionalOnProperty(
matchIfMissing = true)`. With the property unset, whichever wins is decided by
bean ordering - and if OpenAI wins, every chunk is embedded against a billed
remote endpoint instead of the in-process ONNX model that `chunk.embedding` is
sized for.

**The `chunk` table is owned by this project, not by `PgVectorStore`.** Its
autoconfiguration is excluded. Hybrid retrieval needs cosine distance and
`ts_rank` fused in one statement against a table that also carries section and
page; `ChunkJdbcRepository` is the single writer for those rows.

**The postgresql driver is a `compile` dependency.** `com.pgvector.PGvector`
extends `org.postgresql.util.PGobject`, so the driver's types must be on the
compile classpath, not just at runtime.

**Retrieval is RRF-fused, not weighted.** Cosine distance and `ts_rank` are not
on comparable scales, so ranking fuses them by `1/(k + rank)` per arm. The
formula is duplicated in `ChunkJdbcRepository.fuse` so it is unit-testable;
the SQL and that method must not drift.

**Synthesis is deterministic where it can be.** Future-work clustering and the
gap grid use no LLM, so the numbers they report can be cited. Contradictions are
persisted, not recomputed, because a human verdict is recorded against them;
re-running the finder is additive and never deletes a reviewed row.

**`spring-boot-starter-flyway` is required, not just the Flyway libraries.**
Spring Boot 4 split autoconfiguration into per-technology modules. With only
`flyway-database-postgresql` on the classpath, nothing calls `Flyway.migrate()`,
`spring.flyway.*` is silently inert, and the app starts happily and then fails at
runtime with `relation "paper" does not exist`. Adding the starter is what makes
migrations run.

**Vectors are bound as text literals, not `PGvector` objects.**
`com.pgvector.PGvector` needs the driver type registered first
(`PGConnection.addDataType`), and that registration cannot happen from a Spring
bean — Hikari seals its config once the pool has started, so
`setConnectionInitSql` throws. Instead the vector is rendered as
`[0.1,0.2,...]` and cast by the SQL: `?::vector` on insert,
`CAST(:embedding AS vector)` in the search CTE. The `CAST` form is used rather
than `:embedding::vector` because `::` is ambiguous to Spring's named-parameter
parser.

**A bean class with two constructors needs `@Autowired`.** `LibraryLoader` has a
public one and a package-private one for tests. Spring's rule is "use the single
constructor if there is exactly one, otherwise look for a no-arg one", so with
two it fails with `No default constructor found` — a failure that only appears
once the datasource connects, which is why it hid behind the earlier connection
error.

## Testing

```bash
./mvnw test          # offline: BibTeX resolution, chunking, RRF maths, clustering, heading mapping
```

Anything needing Postgres, GROBID or a live model is `@Disabled` and run
manually after `docker compose up -d`:

```bash
./mvnw test -Dtest=IngestPipelineIT -DfailIfNoTests=false
```

## Configuration

All tunables live under `litreview.*` in `application.yaml` and are bound by
`LitreviewProperties`. The ones you are most likely to touch:

| Key | Default | Meaning |
| --- | --- | --- |
| `litreview.chunking.target-chars` | 1200 | preferred chunk size |
| `litreview.chunking.max-chars` | 1800 | hard ceiling before sentence splitting |
| `litreview.search.default-top-k` | 12 | results per search |
| `litreview.search.candidate-pool` | 60 | per-arm candidates before fusion |
| `litreview.synthesis.future-work-similarity-threshold` | 0.62 | cosine above which two statements are one theme |
| `litreview.synthesis.min-papers-per-construct` | 2 | consensus floor |
