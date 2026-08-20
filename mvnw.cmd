@echo off
setlocal
set "PROJECT_DIR=%~dp0"
for /f "tokens=2 delims==" %%a in ('findstr /b "distributionUrl=" "%PROJECT_DIR%.mvn\wrapper\maven-wrapper.properties"') do set "DISTRIBUTION_URL=%%a"
for %%a in ("%DISTRIBUTION_URL%") do set "ARCHIVE_NAME=%%~nxa"
set "MAVEN_VERSION=%ARCHIVE_NAME:apache-maven-=%"
set "MAVEN_VERSION=%MAVEN_VERSION:-bin.zip=%"
if not defined MAVEN_USER_HOME set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
set "MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "MAVEN_CMD=%MAVEN_HOME%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" (
  if not exist "%MAVEN_HOME%" mkdir "%MAVEN_HOME%"
  powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing '%DISTRIBUTION_URL%' -OutFile '%MAVEN_HOME%\maven.zip'; Expand-Archive -Force '%MAVEN_HOME%\maven.zip' '%MAVEN_HOME%'; Remove-Item '%MAVEN_HOME%\maven.zip'"
)
call "%MAVEN_CMD%" %*
