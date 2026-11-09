CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE members (
  id uuid PRIMARY KEY,
  email varchar(254) NOT NULL UNIQUE,
  display_name varchar(200) NOT NULL,
  password_hash text NOT NULL
);
CREATE TABLE sessions (
  id uuid PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id),
  expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_expiry_idx ON sessions(expires_at);

CREATE TABLE projects (
  id uuid PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id),
  name varchar(200) NOT NULL,
  context varchar(20) NOT NULL CHECK (context IN ('EMPLOYMENT','FREELANCE','PERSONAL')),
  external_id varchar(200),
  archived boolean NOT NULL DEFAULT false,
  UNIQUE(member_id, id),
  UNIQUE(member_id, external_id)
);
CREATE TABLE time_entries (
  id uuid PRIMARY KEY,
  member_id uuid NOT NULL REFERENCES members(id),
  project_id uuid,
  start_at timestamptz NOT NULL,
  end_at timestamptz,
  kind varchar(20) NOT NULL CHECK (kind IN ('WORK','PERSONAL')),
  description varchar(4000),
  source varchar(20) NOT NULL CHECK (source IN ('MANUAL','CLOCK','LEGACY_IMPORT')),
  external_id varchar(200),
  legacy_payload text,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  FOREIGN KEY (member_id, project_id) REFERENCES projects(member_id, id),
  CHECK (end_at IS NULL OR end_at >= start_at),
  UNIQUE(member_id, external_id),
  EXCLUDE USING gist (member_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&)
);
CREATE UNIQUE INDEX time_entries_one_open ON time_entries(member_id) WHERE end_at IS NULL;
CREATE INDEX time_entries_member_start ON time_entries(member_id, start_at);
CREATE INDEX time_entries_member_project ON time_entries(member_id, project_id);
