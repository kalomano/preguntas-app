# Mis Preguntas — extensión Chrome

Extensión separada para guardar una pregunta corregida de Testazo o Bombea en
[Mis Preguntas](https://preguntas-app.onrender.com/).

## Instalar

1. Descarga esta carpeta del repositorio o descomprime el ZIP de la extensión.
2. Abre `chrome://extensions` en Chrome y activa **Modo de desarrollador**.
3. Pulsa **Cargar descomprimida** y elige la carpeta `chrome-extension`.
4. Entra en Mis Preguntas y selecciona tu usuario. Después abre Testazo o Bombea.

La extensión solo se activa en Testazo, Bombea y Mis Preguntas para guardar la pregunta.
El botón se integra junto a los controles de cada web y no sustituye ni modifica los
botones de favoritos existentes.

## Uso

1. Abre una pregunta y asegúrate de que las cuatro opciones y la respuesta correcta estén visibles.
2. Pulsa el botón de guardar integrado junto a los controles de Testazo o Bombea.
3. Mis Preguntas procesa el guardado en una pestaña de fondo, que se cierra automáticamente al confirmar el éxito.

El enlace de origen se toma de la página abierta. El tema se detecta de prefijos visibles
como el de Bombea («Decreto Legislativo 1/2006») y de alias conocidos de Testazo.
Si no aparece un prefijo reconocible, la pregunta se guarda como **Sin tema** y podrás
asignarlo desde la aplicación.

## Seguridad y compatibilidad

- La extensión no sustituye el código de favoritos existente y nunca elimina datos.
- El guardado usa el flujo de importación de la aplicación y conserva la pregunta,
  sus cuatro opciones, respuesta correcta, tema y URL de origen.
- La extensión solo abre el dominio fijo de Mis Preguntas y solo acepta páginas de Testazo
  o Bombea incluidas en su manifiesto.
