@echo off
rem Shared helpers for the docker-*.bat scripts. Not meant to be run directly.
rem   call "%~dp0_common.bat" root           -> errorlevel 0 when the current folder is the project root
rem   call "%~dp0_common.bat" check          -> errorlevel 0 when the Docker CLI, engine and Compose V2 are usable
rem   call "%~dp0_common.bat" env            -> errorlevel 0 when .env exists in the project root
rem   call "%~dp0_common.bat" pause "%~f0"   -> pauses only when that script was started by double-click
rem Messages print paths with delayed expansion, so folders containing ( ) or & do not break them.
setlocal EnableDelayedExpansion
goto :%~1

:root
if not exist "docker-compose.yml" (
    echo [ERROR] docker-compose.yml not found in "!CD!". Keep the scripts in the project's scripts folder.
    exit /b 1
)
exit /b 0

:check
where docker >nul 2>&1
if errorlevel 1 (
    echo [ERROR] The docker command was not found. Install Docker Desktop: see README-Docker.md, section 2.
    exit /b 1
)
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] The Docker engine is not running. Start Docker Desktop, wait until it says "Engine running", retry.
    exit /b 1
)
docker compose version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Compose V2 is not available. Update Docker Desktop.
    exit /b 1
)
exit /b 0

:env
if not exist ".env" (
    echo [ERROR] .env not found in "!CD!". Copy .env.example to .env and replace every CHANGE_ME value.
    exit /b 1
)
exit /b 0

:pause
rem Explorer starts a double-clicked script as:   cmd.exe /c ""<full path>" "
rem PowerShell and a cmd prompt use other forms (no quote-space-quote after the path), so they never pause.
if defined NOPAUSE exit /b 0
set "CL=!cmdcmdline!"
set "SCRIPT=%~2"
rem The path goes through a FOR variable: expanded after cmd has parsed ( ) and &, so they cannot break the line.
rem The search text contains quotes, so the substitution is done in SET (where quotes are harmless) and the IF
rem compares the results with delayed expansion, after parsing.
for /f "delims=" %%S in ("!SCRIPT!") do set "REST=!CL:"%%S" "=!"
if not "!REST!"=="!CL!" pause
exit /b 0
