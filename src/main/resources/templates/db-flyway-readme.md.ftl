# Database migrations (Flyway)

The database this project was generated from is version 1: on its first start against it, Flyway records that
version as the baseline (`spring.flyway.baseline-on-migrate`) and changes nothing.

Put every later change here as `V2__what_it_does.sql`, `V3__…`, applied once, in order, on every database.
<#if checks.flyway == "SCHEMA">
`V1__baseline.sql` holds the schema itself: an empty database (a test container, a new environment) is created
from it, so it must stay as it is.
</#if>

The application's database user needs the right to create Flyway's history table (`flyway_schema_history`) and to
run your migrations; a read-only login is not enough.
