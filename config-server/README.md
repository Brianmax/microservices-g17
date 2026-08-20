# Config Server classroom scaffold

This standalone service is deliberately not part of the root Maven reactor or Docker Compose stack.

Classroom work remains to:

- Enable the Config Server in the application class.
- Choose and configure its port.
- Select and configure a native or Git-backed configuration repository.
- Add Config clients to the other services.
- Move appropriate non-secret configuration into the repository.

Production secrets must not be committed to the configuration repository.
