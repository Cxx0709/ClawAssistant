@echo off
set JAVA_HOME=C:\dev\jdk-17
set PATH=C:\dev\jdk-17\bin;%PATH%
cd /d C:\Users\han\ClawAssistant
call "C:\dev\apache-maven-3.9.16\bin\mvn.cmd" spring-boot:run -Dmaven.test.skip=true