# Way C - Spring Cloud Config Server + Bus refresh

Not implemented yet. Planned contents:

- `config-server-deployment.yaml` - the Config Server, backed by a Git repository
- `rabbitmq.yaml` - RabbitMQ, used **only** as the Spring Cloud Bus transport
- `deployment.yaml` - probe-service with `spring.config.import: configserver:...`

Activation: `POST /actuator/busrefresh` on the Config Server broadcasts a refresh
event to every replica at once.

Known cost to measure: the Config Server is a startup dependency. If it is
unreachable when a pod starts, the pod may fail to start at all. That is the
"source unavailable at startup" scenario.
