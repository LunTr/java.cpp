@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if not exist "target\demo-all.jar" (
  echo Build first: mvnw.cmd clean verify
  exit /b 2
)
set "JAVA_CMD=java"
if defined JAVA_HOME set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
"%JAVA_CMD%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "target\demo-all.jar" io.github.yaulun.demo.DependencyCheckMain %*
exit /b %ERRORLEVEL%
