# Mis Preguntas — extensión Chrome

Extensión separada para guardar una pregunta corregida de Testazo o Bombea en
[Mis Preguntas](https://preguntas-app.onrender.com/).

## Instalar

1. Descarga esta carpeta del repositorio o descomprime el ZIP de la extensión.
2. Abre `chrome://extensions` en Chrome y activa **Modo de desarrollador**.
3. Pulsa **Cargar descomprimida** y elige la carpeta `chrome-extension`.
4. Entra en Mis Preguntas y selecciona tu usuario. Después abre Testazo o Bombea.

No requiere claves ni permisos para leer el contenido de otras páginas. Solo se activa
en `app.testazo.com` y `bombea.madrid`. El botón nuevo es independiente: no sustituye
ni modifica botones de favoritos que ya existan.

## Uso

1. Abre una pregunta y asegúrate de que las cuatro opciones y la respuesta correcta estén visibles.
2. Pulsa **Guardar en Mis Preguntas**.
3. Se abrirá la aplicación, donde la pregunta se importa a tu usuario actual.

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
