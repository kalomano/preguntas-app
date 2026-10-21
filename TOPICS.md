# Temas opcionales

Las 35 etiquetas y sus identificadores estables están en `topics.py`. No se
admiten otros temas. Las preguntas existentes y los guardados sin `topic_id`
permanecen con `NULL` (Sin tema). Las extensiones pueden seguir enviando el
mismo POST a `/confirm-save`.

En la confirmación de una captura se puede elegir tema. En Guardadas, abrir
«Tema» permite cambiarlo o quitarlo; «Preguntas por tema» muestra los recuentos
del usuario, ordenados por cantidad, incluyendo Sin tema y temas con cero.
En Inicio, «Practicar por tema (opcional)» permite elegir un tema, Sin tema o
Todos. Los botones habituales siguen practicando con Todos.

## Migración y despliegue

El arranque existente llama a `migrate_topics`: añade únicamente una columna
SMALLINT nullable con CHECK entre 1 y 35 y ADD COLUMN IF NOT EXISTS. No hay
backfill, borrado, índice nuevo ni reclasificación automática. La espera por
el bloqueo DDL se limita a 3 segundos. Si el arranque registra un fallo de
inicialización, comprobar el registro y volver a desplegar cuando no haya
transacciones largas. La aplicación ya registraba los errores de arranque;
no se ha cambiado ese comportamiento.

La actualización de tema usa tanto id de pregunta como user_id de la sesión
y requiere un token del formulario. Se conserva el sistema actual de perfiles
seleccionables; esta funcionalidad no añade autenticación por contraseña.

Antes de integrar, ejecutar `python -m unittest -v test_topics`. GitHub Actions
ejecuta las mismas pruebas en memoria y con PostgreSQL 16 aislado, incluyendo
una migración repetida sobre preguntas antiguas y la restricción de la BD.
TEST_DATABASE_URL es exclusivamente una base de pruebas: nunca producción.

Para volver a la versión previa, revertir el commit y desplegar; dejar la
columna en la base de datos. La versión previa ignora la columna adicional.
No eliminar columnas ni datos para hacer rollback.
