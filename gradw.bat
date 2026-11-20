@echo off
setlocal
if not defined JAVA_HOME set JAVA_HOME=D:\Skills\Remote\remotehub\.tooling\jdk\zulu17.70.113-ca-jdk17.0.21.0.3-win_x64
& "C:\Users\Hany-Tech\.gradle\wrapper\dists\gradle-8.13-bin\928fl043lon0uu06jzrrnc41m\gradle-8.13\bin\gradle.bat" %*
