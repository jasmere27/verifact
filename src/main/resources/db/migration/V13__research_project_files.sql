-- Files in a capstone project (ADR-23): chapter drafts and research papers the student uploaded. Only the extracted
-- text and the analysis are kept, never the file. summary_json is small (shown in the project); detail_json has the
-- text and full analysis (loaded when the file is opened). Deleted with the project.
CREATE TABLE research_project_files (
    id            UUID          PRIMARY KEY,
    project_id    UUID          NOT NULL REFERENCES research_projects (id) ON DELETE CASCADE,
    kind          VARCHAR(8)    NOT NULL,
    label         VARCHAR(80),
    file_name     VARCHAR(200),
    created_at    TIMESTAMPTZ   NOT NULL,
    summary_json  TEXT          NOT NULL,
    detail_json   TEXT          NOT NULL
);

CREATE INDEX idx_research_project_files_project ON research_project_files (project_id, created_at DESC);
