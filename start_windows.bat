@echo off
setlocal
cd /d %~dp0

echo ============================================
echo   App de preguntas - instalacion y arranque
echo ============================================

echo.
where py >nul 2>nul
if %errorlevel%==0 (
  set PY=py
) else (
  where python >nul 2>nul
  if %errorlevel%==0 (
    set PY=python
  ) else (
    echo ERROR: Python no esta instalado o no esta en PATH.
    echo Instala Python desde https://www.python.org/downloads/
    pause
    exit /b 1
  )
)

if not exist .venv (
  echo Creando entorno virtual...
  %PY% -m venv .venv
)

call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements.txt

echo.
echo Comprobando Tesseract...
set TESS=
if exist "C:\Program Files\Tesseract-OCR\tesseract.exe" set TESS=C:\Program Files\Tesseract-OCR\tesseract.exe
if not defined TESS (
  for /f "delims=" %%T in ('where tesseract 2^>nul') do if not defined TESS set TESS=%%T
)
if not defined TESS (
  echo ERROR: no encuentro Tesseract OCR.
  echo Instala Tesseract OCR y vuelve a ejecutar este archivo.
  echo Consulta el README.txt para el enlace y los pasos.
  pause
  exit /b 1
)

echo Tesseract encontrado en: %TESS%

echo.
echo Comprobando idioma espanol...
"%TESS%" --list-langs | findstr /i /x "spa" >nul
if %errorlevel% neq 0 (
  echo AVISO: Tesseract esta instalado, pero falta el idioma spa.
  echo Instala el idioma Spanish y vuelve a ejecutar.
  pause
  exit /b 1
)

echo.
echo Arrancando la aplicacion...
python app.py
pause
