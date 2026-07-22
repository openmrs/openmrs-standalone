# OpenMRS 3 (O3) Standalone

[![Download O3 Standalone](https://img.shields.io/badge/Download-O3_Standalone-blue?style=for-the-badge)](https://nightly.link/openmrs/openmrs-standalone/workflows/build-o3-standalone/openmrs-emr3/openmrs-standalone-o3.zip)

Download the **O3 Standalone** — a single zip with everything included (OpenMRS 3 Reference
Application 3.7.1, demo data fully initialised, embedded database), no Docker required.
Unzip it and run `openmrs-standalone.jar` (needs **Java 17 or newer**). The download above always
tracks the latest build of the [`openmrs-emr3`](https://github.com/openmrs/openmrs-standalone/tree/openmrs-emr3) branch.

The standalone is built for evaluation, demos, training, and small single-machine use — and it can
run a small single-site clinic too. Running any OpenMRS in production carries the same operator
responsibilities (see [Running in production](docs/user-guide.md#running-in-production)); those are
not specific to the standalone.

## Documentation

- **[User guide](docs/user-guide.md)** — requirements, running the standalone, running in
  production, upgrading a live install, the setup wizard, and the UI / command-line reference.
- **[Developer guide](docs/developer.md)** — building from source, testing a code change locally,
  extracting DB dumps, running from Eclipse, the distribution layout, and internals.
- **[Release runbook](docs/releasing.md)** — for maintainers rebuilding and publishing the
  standalone for a new O3 Reference Application release.

## Outgrowing the standalone

The standalone embeds its database and runs as a single node. Move to a full server deployment when
you need more concurrent users, high availability, or a database you patch and scale independently:

- **[Migrating to a production Docker deployment](docs/migrating-to-docker-o3.md)** — the official O3 Docker distribution (recommended).
- **[Migrating to a Tomcat + external database deployment](docs/migrating-to-tomcat-server.md)** — a conventional bare-metal/VM stack.

These two runbooks cover taking a standalone to a server, but they aren't the only ways to run
OpenMRS 3. It can also run as the reference application's **multi-container Docker** layout
(separate frontend/gateway/backend/db — for high availability or scaling the frontend
independently), against an **external managed database**, or — for development — via the
**OpenMRS SDK**; community **Kubernetes/Ansible** setups exist too, but those just automate the
same runtimes. The Docker distribution is the recommended production path.
