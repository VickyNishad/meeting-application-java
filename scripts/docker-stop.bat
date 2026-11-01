@echo off
setlocal
rem Stops the containers. Data is kept: volumes are never removed by this script.
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

echo Stopping services %*...
docker compose stop %*
if errorlevel 1 (
    echo [FAILED] Could not stop the services.
    set "RC=1"
    goto :end
)
echo [OK] Stopped. Database data is kept; start again with scripts\docker-start.bat

:end
popd
:done
call "%~dp0_common.bat" pause "%~f0"
exit /b %RC%
