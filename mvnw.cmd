@echo off
setlocal
if exist "%LOCALAPPDATA%\Programs\apache-maven-3.9.6\bin\mvn.cmd" (
    "%LOCALAPPDATA%\Programs\apache-maven-3.9.6\bin\mvn.cmd" %*
) else (
    mvn %*
)
