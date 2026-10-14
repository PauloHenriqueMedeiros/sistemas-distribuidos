#!/bin/sh
# Compila o projeto e gera Servidor.jar e Cliente.jar (precisa do JDK instalado)
cd "$(dirname "$0")"
rm -rf bin && mkdir bin
javac -encoding UTF-8 -d bin $(find src -name "*.java") || { echo "ERRO NA COMPILACAO"; exit 1; }
jar cfe Servidor.jar servidor.Servidor -C bin comum -C bin servidor
jar cfe Cliente.jar cliente.Cliente -C bin comum -C bin cliente
echo "Pronto: Servidor.jar e Cliente.jar gerados."
