@echo off
rem Starts the Java controller. All logic is in tools\benchctl; this file only builds the jar and runs it.
setlocal
set "ROOT=%~dp0"
call "%ROOT%mvnw.cmd" -q -B -f "%ROOT%tools\benchctl\pom.xml" -DskipTests package
if errorlevel 1 exit /b %errorlevel%
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" -jar "%ROOT%tools\benchctl\target\benchctl.jar" %*
exit /b %errorlevel%
