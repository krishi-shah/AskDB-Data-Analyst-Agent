@echo off
rem Opens the AskDB desktop window. Build first with: mvnw.cmd -q package -DskipTests
start "" javaw -cp "%~dp0target\askdb.jar" ca.yorku.askdb.presentation.gui.MainWindow
