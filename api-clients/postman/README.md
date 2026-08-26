# Colección de Postman

Importa `Virtual-Bank.postman_collection.json` en Postman. La colección contiene todos los
endpoints declarados en los seis controladores del proyecto. Todas las solicitudes usan la única
variable `gatewayBaseUrl`, cuyo valor predeterminado es `http://localhost:8080`.

## Flujo recomendado

1. Levanta el proyecto con `docker compose up -d --build`.
2. Ejecuta **Registrar usuario** y **Iniciar sesión**. Sus scripts guardan `userId`,
   `accessToken` y `refreshToken` automáticamente.
3. Ejecuta **Abrir cuenta origen**, **Abrir cuenta destino** y **Depositar**. También guardan los
   IDs necesarios para las operaciones posteriores.
4. Ejecuta **Crear transferencia** y después las consultas deseadas.

Las rutas administrativas necesitan un token con los permisos de `ADMIN`. Las rutas internas se
incluyen porque pertenecen a los controladores, pero en esta versión educativa no exigen una
credencial entre servicios.

Todas las operaciones se envían a través del API Gateway; no es necesario configurar los puertos
internos de los microservicios en Postman.

El gateway también contiene predicates específicos para las operaciones internas de Banking y
Exchange Rate, por lo que todos los métodos de los controladores son accesibles mediante el puerto
8080. En esta versión educativa esas operaciones internas no tienen autenticación entre servicios;
no deben exponerse de esta forma en un entorno de producción.
