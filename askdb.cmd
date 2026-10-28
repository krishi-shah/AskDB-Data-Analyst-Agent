@echo off
rem Runs the AskDB command line. Build first with: mvnw.cmd -q package -DskipTests
java -jar "%~dp0target\askdb.jar" %*
