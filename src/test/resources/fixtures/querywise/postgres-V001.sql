-- Source: querywise pack/postgres/V001__schema.sql (MIT License, (c) the querywise authors), copied unchanged below.
-- Used as a second real-world PostgreSQL fixture: assigned integer keys, CHECK and multi-column UNIQUE constraints, FK chains.
-- Generated for postgres. Single-operator lab only. See README before execution.
CREATE TABLE crews (
	id INTEGER NOT NULL, 
	name VARCHAR(120) NOT NULL, 
	specialty VARCHAR(32) NOT NULL, 
	PRIMARY KEY (id)
);

CREATE TABLE sites (
	id INTEGER NOT NULL, 
	name VARCHAR(120) NOT NULL, 
	region VARCHAR(32) NOT NULL, 
	latitude NUMERIC(14, 3) NOT NULL, 
	longitude NUMERIC(14, 3) NOT NULL, 
	PRIMARY KEY (id)
);

CREATE TABLE contracts (
	id INTEGER NOT NULL, 
	site_id INTEGER NOT NULL, 
	starts_on DATE NOT NULL, 
	ends_on DATE NOT NULL, 
	rate_micros INTEGER NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT ck_contracts_0 CHECK (ends_on >= starts_on), 
	CONSTRAINT ck_contracts_1 CHECK (rate_micros >= 0), 
	FOREIGN KEY(site_id) REFERENCES sites (id)
);

CREATE TABLE turbines (
	id INTEGER NOT NULL, 
	site_id INTEGER NOT NULL, 
	serial VARCHAR(32) NOT NULL, 
	capacity_kw INTEGER NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT ck_turbines_0 CHECK (capacity_kw > 0), 
	CONSTRAINT uq_turbines_0 UNIQUE (serial), 
	FOREIGN KEY(site_id) REFERENCES sites (id)
);

CREATE TABLE outages (
	id INTEGER NOT NULL, 
	turbine_id INTEGER NOT NULL, 
	started_on DATE NOT NULL, 
	duration_minutes INTEGER NOT NULL, 
	reason VARCHAR(32) NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT ck_outages_0 CHECK (duration_minutes >= 0), 
	FOREIGN KEY(turbine_id) REFERENCES turbines (id)
);

CREATE TABLE readings (
	id INTEGER NOT NULL, 
	turbine_id INTEGER NOT NULL, 
	observed_on DATE NOT NULL, 
	energy_wh BIGINT NOT NULL, 
	quality VARCHAR(32) NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT ck_readings_0 CHECK (quality IN ('valid','estimated','invalid')), 
	CONSTRAINT ck_readings_1 CHECK (energy_wh >= 0), 
	CONSTRAINT uq_readings_0 UNIQUE (turbine_id, observed_on), 
	FOREIGN KEY(turbine_id) REFERENCES turbines (id)
);

CREATE TABLE settlements (
	id INTEGER NOT NULL, 
	contract_id INTEGER NOT NULL, 
	period_on DATE NOT NULL, 
	paid_cents BIGINT NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT uq_settlements_0 UNIQUE (contract_id, period_on), 
	FOREIGN KEY(contract_id) REFERENCES contracts (id)
);

CREATE TABLE work_orders (
	id INTEGER NOT NULL, 
	outage_id INTEGER NOT NULL, 
	crew_id INTEGER NOT NULL, 
	cost_cents BIGINT NOT NULL, 
	status VARCHAR(32) NOT NULL, 
	PRIMARY KEY (id), 
	CONSTRAINT ck_work_orders_0 CHECK (cost_cents >= 0), 
	FOREIGN KEY(outage_id) REFERENCES outages (id), 
	FOREIGN KEY(crew_id) REFERENCES crews (id)
);

CREATE INDEX ix_contracts_site_id ON contracts (site_id);

CREATE INDEX ix_turbines_site_id ON turbines (site_id);

CREATE INDEX ix_outages_turbine_id ON outages (turbine_id);

CREATE INDEX ix_readings_turbine_id ON readings (turbine_id);

CREATE INDEX ix_settlements_contract_id ON settlements (contract_id);

CREATE INDEX ix_work_orders_crew_id ON work_orders (crew_id);

CREATE INDEX ix_work_orders_outage_id ON work_orders (outage_id);
