PREGUNTAS APP ONLINE

Esta versión está preparada para Render + Supabase.

ARCHIVOS IMPORTANTES
- app.py: aplicación Flask
- Dockerfile: instala Tesseract automáticamente EN EL SERVIDOR. NO necesitas instalar Docker en tu PC para desplegar en Render.
- requirements.txt: dependencias Python
- migrate_sqlite.py: copia tus preguntas locales de data/questions.db a Supabase

VARIABLES DE RENDER
- DATABASE_URL = cadena de conexión Pooler de Supabase
- SECRET_KEY = cadena aleatoria larga
- APP_PASSWORD = contraseña que compartirás con tus amigos

NOTA
Las imágenes subidas se guardan solo temporalmente durante el análisis y luego se borran.
Las preguntas se guardan en Supabase, no en el disco de Render.
