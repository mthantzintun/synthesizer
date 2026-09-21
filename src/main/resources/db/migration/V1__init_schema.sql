-- ============================================================
-- Extensions
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- fuzzy title/author matching later

-- ============================================================
-- Core: paper + ingest status tracking
-- ============================================================
-- TEXT + CHECK rather than a native Postgres ENUM: the postgresql JDBC driver
-- requires explicit ::ingest_status casts for native enums, which fights with
-- plain Hibernate @Enumerated(STRING) mapping. A CHECK constraint gives the
-- same guarantee without that friction.
CREATE TABLE paper (
    id              BIGSERIAL PRIMARY KEY,
    citekey         TEXT NOT NULL UNIQUE,
    doi             TEXT,
    title           TEXT NOT NULL,
    authors         TEXT NOT NULL,          -- "Smith, J.; Jones, K." normalized form
    year            INT,
    venue           TEXT,
    pdf_path        TEXT,                    -- local path to source PDF
    status          TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','METADATA_OK','GROBID_OK','CHUNKED','EMBEDDED','EXTRACTED','FAILED')),
    failure_reason  TEXT,                     -- last error, if status = FAILED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_paper_status ON paper (status);
CREATE INDEX idx_paper_year ON paper (year);

-- ============================================================
-- Chunk: section-aware text units + embeddings
-- ============================================================
CREATE TABLE chunk (
    id          BIGSERIAL PRIMARY KEY,
    paper_id    BIGINT NOT NULL REFERENCES paper(id) ON DELETE CASCADE,
    section     TEXT,                        -- 'Introduction', 'Methodology', etc. (paper's own heading)
    canonical_section TEXT,                  -- normalized IMRaD slot: 'METHOD', 'RESULTS', 'FUTURE_WORK', ...
    ordinal     INT NOT NULL,                -- position within the paper
    page        INT,                         -- 1-based page the chunk starts on, from GROBID coordinates
    content     TEXT NOT NULL,               -- heading-prefixed text that was embedded
    embedding   VECTOR(384),                 -- all-MiniLM-L6-v2 (local ONNX, no API cost)
    tsv         TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_chunk_paper ON chunk (paper_id);
CREATE INDEX idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_tsv ON chunk USING gin (tsv);
CREATE INDEX idx_chunk_section ON chunk (section);
CREATE INDEX idx_chunk_canonical ON chunk (canonical_section);

-- ============================================================
-- Extraction: Phase 4 structured concept-matrix data
-- ============================================================
CREATE TABLE extraction (
    id                      BIGSERIAL PRIMARY KEY,
    paper_id                BIGINT NOT NULL REFERENCES paper(id) ON DELETE CASCADE,
    research_question       TEXT,
    theoretical_lens        TEXT,
    method                  TEXT,
    constructs              TEXT[],           -- array; query with ANY() / && for overlap
    key_finding             TEXT,
    stated_limitation       TEXT,
    future_work_suggested   TEXT,
    context_sector          TEXT,
    supporting_quote        TEXT,
    quote_page              TEXT,
    model_used              TEXT,             -- e.g. 'anthropic/claude-sonnet-4.5' — track for reproducibility
    extracted_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (paper_id)                          -- one extraction per paper; re-run = update, not duplicate
);

CREATE INDEX idx_extraction_constructs ON extraction USING gin (constructs);
CREATE INDEX idx_extraction_context ON extraction (context_sector);

-- ============================================================
-- Synthesis outputs (persisted, not throwaway query results)
-- ============================================================
CREATE TABLE contradiction (
    id                  BIGSERIAL PRIMARY KEY,
    construct           TEXT NOT NULL,
    paper_a_id          BIGINT NOT NULL REFERENCES paper(id),
    claim_a             TEXT NOT NULL,
    paper_b_id          BIGINT NOT NULL REFERENCES paper(id),
    claim_b             TEXT NOT NULL,
    possible_moderator  TEXT,
    reviewed            BOOLEAN NOT NULL DEFAULT FALSE,   -- you mark true once you've checked it's real
    verdict              TEXT,                             -- your own note: 'real gap' / 'false positive' / etc.
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_contradiction_construct ON contradiction (construct);

CREATE TABLE future_work_cluster (
    id              BIGSERIAL PRIMARY KEY,
    theme           TEXT NOT NULL,
    paper_ids       BIGINT[] NOT NULL,        -- which papers raised this
    paper_count     INT NOT NULL,             -- denormalized for quick sort by consensus strength
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_future_work_count ON future_work_cluster (paper_count DESC);

-- ============================================================
-- Ops: cost/rate tracking for OpenRouter calls
-- ============================================================
CREATE TABLE api_call_log (
    id              BIGSERIAL PRIMARY KEY,
    paper_id        BIGINT REFERENCES paper(id) ON DELETE SET NULL,
    call_type       TEXT NOT NULL,            -- 'extraction' | 'contradiction' | 'future_work' | 'steelman'
    model           TEXT NOT NULL,
    tokens_in       INT,
    tokens_out      INT,
    cost_usd        NUMERIC(10,6),
    success         BOOLEAN NOT NULL,
    error_message   TEXT,
    called_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_api_log_paper ON api_call_log (paper_id);
CREATE INDEX idx_api_log_type ON api_call_log (call_type);

-- ============================================================
-- updated_at trigger for paper table
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_paper_updated_at
    BEFORE UPDATE ON paper
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();