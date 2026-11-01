@echo off
setlocal
rem Restarts running services: all, or the ones given, e.g.  docker-restart.bat backend
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

echo Restarting services %*...
docker compose restart %*
if errorlevel 1 (
    echo [FAILED] Could not restart the services.
    set "RC=1"
    goto :end
)
echo [OK] Restarted.
docker compose ps

:end
popd
:done
call "%~dp0_common.bat" pause "%~f0"
exit /b %RC%
