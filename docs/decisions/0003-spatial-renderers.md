# ADR 0003: Separate canonical gallery queries from spatial renderers

Status: accepted

## Context

The product includes 3D timeline, map, terrain, and compass views, but these views have different hardware, data, and accessibility constraints.

## Decision

All views consume shared catalog queries, selection, filters, and asset identity. Grid and 2D timeline remain canonical. Map is an adapter over spatial queries. Terrain is a separate renderer over elevation data. Compass is a sensor-driven bearing renderer. None owns duplicate catalog logic.

## Consequences

- 3D and sensor-heavy features can be disabled or replaced without losing core functionality.
- Devices lacking elevation data, GPU capability, sensors, or motion tolerance retain complete 2D workflows.
- Spatial confidence/provenance belongs in the domain model, not only in scene code.
