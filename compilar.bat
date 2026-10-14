@echo off
REM Compila o projeto e gera Servidor.jar e Cliente.jar (precisa do JDK instalado)
if exist bin rmdir /s /q bin
mkdir bin
dir /s /b src\*.java > fontes.txt
javac -encoding UTF-8 -d bin @fontes.txt
if errorlevel 1 (
  del fontes.txt
  echo ERRO NA COMPILACAO
  pause
  exit /b 1
)
del fontes.txt
jar cfe Servidor.jar servidor.Servidor -C bin comum -C bin servidor
jar cfe Cliente.jar cliente.Cliente -C bin comum -C bin cliente
echo Pronto: Servidor.jar e Cliente.jar gerados.
pause
