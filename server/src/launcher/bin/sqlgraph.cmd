@echo off
rem sqlgraph launcher (Windows): runs with the bundled Java runtime
setlocal
set "APP_HOME=%~dp0.."
"%APP_HOME%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -jar "%APP_HOME%\lib\sqlgraph-mcp.jar" %*
