@echo off
setlocal
rem Starts the stack in the background. Optional: only some services, e.g.  docker-start.bat postgres pgadmin
rem Run from anywhere: the script switches to the project root (pushd also works from a network share).
set "RC=0"
pushd "%~dp0.." 2>nul
if errorlevel 1 (
    echo [ERROR] Cannot open the project folder, one level above this script.
    set "RC=1"
    goto :done
)
call "%~dp0_common.bat" root || (set "RC=1" & goto :end)
call "%~dp0_common.bat" env || (set "RC=1" & goto :end)
call "%~dp0_common.bat" check || (set "RC=1" & goto :end)

if "%~1"=="" (echo Starting all services...) else (echo Starting: %*)
docker compose up -d %*
if errorlevel 1 (
    echo [FAILED] Could not start the services. See the messages above, or run scripts\docker-logs.bat
    set "RC=1"
    goto :end
)
echo [OK] Services started. Addresses are in the PORTS column below; pgAdmin is the pgadmin row.
docker compose ps

:end
popd
:done
call "%~dp0_common.bat" pause "%~f0"
exit /b %RC%
