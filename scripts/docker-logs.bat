@echo off
setlocal
rem Shows logs: all services, or one, e.g.  docker-logs.bat postgres   Follow with -f:  docker-logs.bat postgres -f
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

docker compose logs --tail 200 %*
if errorlevel 1 (
    echo [FAILED] Could not read the logs. Check the service name with scripts\docker-status.bat
    set "RC=1"
)

:end
popd
:done
call "%~dp0_common.bat" pause "%~f0"
exit /b %RC%
