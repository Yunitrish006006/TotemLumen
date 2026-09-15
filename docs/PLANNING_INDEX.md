# Totem Lumen Planning Index

This file is the repository index for design plans and decision tables. Architectural decisions should be recorded in GitHub docs instead of existing only in chat history.

| Area | Primary document | What is recorded |
| --- | --- | --- |
| Whole project architecture | [`ARCHITECTURE.md`](ARCHITECTURE.md) | Vulkan/client vs common/server boundaries, authority model, package direction |
| Renderer phases | [`ROADMAP.md`](ROADMAP.md) | P0+ renderer milestones and runtime validation gates |
| Server gameplay-light phases | [`GAMEPLAY_LIGHTING_ROADMAP.md`](GAMEPLAY_LIGHTING_ROADMAP.md) | GL0–GL5 implementation/validation roadmap |
| Authoritative gameplay-light design | [`SERVER_GAMEPLAY_LIGHTING.md`](SERVER_GAMEPLAY_LIGHTING.md) | storage, propagation, spawn policy, sky/environment, budgets, resource estimates, validation matrix |
| Data-pack block lighting | [`LIGHTING_WORLD_RULES.md`](LIGHTING_WORLD_RULES.md) | `emission_color`, `gameplay_strength`, reload/sync semantics |
| Vulkan interoperability | [`VULKAN_INTEROP.md`](VULKAN_INTEROP.md) | Minecraft Vulkan ownership/interoperability constraints |

## Current accepted server-lighting decisions

| Topic | Accepted direction |
| --- | --- |
| Authority | Server owns gameplay light; client owns presentation |
| Fixed block light | packed RGB section cache |
| Flickering visual lights | stable gameplay representative strength |
| True gameplay dynamic lights | future cached-spatial + dynamic-state layer |
| Sky/day cycle | query-time sky/time semantics, no per-tick RGB rebuild |
| Sun angle | client renders directional shadows; server does not ray trace sun |
| Dimension ambient | optional query-time dimension RGB profile |
| Nether mobs | reduced red sensitivity, especially environment red |
| Spawn query | O(1) stable lookup + spectral sensitivity + Vanilla sky/dimension checks |
| Backlog safety | dirty lighting suppresses hostile dark-spawn approval |
| Server GI | excluded |
| RGB persistence | excluded in first implementation |
| RGB network sync | excluded; only compact rules sync to clients |

## Current resource-planning tables

The canonical detailed estimates live in `SERVER_GAMEPLAY_LIGHTING.md`. The headline budgets are:

| Resource | Baseline target |
| --- | --- |
| RGB cache | 8 KiB per allocated lit section |
| Idle CPU | < 0.2 ms/tick |
| Normal CPU | < 0.75 ms/tick |
| Heavy update CPU | < 1.5 ms/tick typical |
| Hard lighting time ceiling | about 2 ms/tick server-wide |
| Work ceiling | 20,000 work units/tick server-wide |
| Persistent world data | 0 bytes for RGB cache in v1 |
| Steady-state custom network traffic | approximately zero |

These CPU numbers are engineering targets, not benchmark claims. Runtime measurements are required before optimization decisions.
