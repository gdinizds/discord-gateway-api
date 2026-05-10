-- H2 does not support CREATE SCHEMA with IF NOT EXISTS via SET SCHEMA
-- Using SET SCHEMA to set the default schema for subsequent statements
CREATE SCHEMA IF NOT EXISTS GATEWAY;
SET SCHEMA GATEWAY;

-- ENUMs as domain aliases (H2 does not support PostgreSQL CREATE TYPE ... AS ENUM)
-- Constraints are enforced via CHECK on each column definition
