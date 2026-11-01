@echo off
setlocal
rem Builds the application images: backend, web. Optional: one service, e.g.  docker-build.bat backend
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

echo Building images %*...
docker compose build %*
if errorlevel 1 (
    echo [FAILED] Build failed. See the messages above.
    set "RC=1"
    goto :end
)
echo [OK] Images built. Apply them with scripts\docker-start.bat

:end
popd
:done
call "%~dp0_common.bat" pause "%~f0"
exit /b %RC%
