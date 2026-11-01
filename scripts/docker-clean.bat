@echo off
setlocal EnableDelayedExpansion
rem Frees disk space, one choice at a time. Nothing is deleted without being chosen, and a volume only after typing
rem its confirmation phrase. Without keyboard input (pipe, scheduled task) it exits without deleting anything.
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

:menu
echo.
echo   1  Remove stopped containers of this project
echo   2  Remove unused dangling images
echo   3  Remove unused networks
echo   4  DELETE the PostgreSQL database volume, with all data in it
echo   5  DELETE the pgAdmin settings volume: saved servers, preferences
echo   0  Exit
set "CHOICE="
set /p "CHOICE=Choose: "
rem No answer: Enter alone, or no keyboard at all (end of input). Never loop on it.
if not defined CHOICE (
    echo.
    echo No choice entered: exiting. Nothing was deleted.
    goto :end
)
if "!CHOICE!"=="1" goto :containers
if "!CHOICE!"=="2" goto :images
if "!CHOICE!"=="3" goto :networks
if "!CHOICE!"=="4" goto :pgdata
if "!CHOICE!"=="5" goto :pgadmin
if "!CHOICE!"=="0" goto :end
echo Unknown choice "!CHOICE!".
set /a "INVALID+=1"
if !INVALID! geq 3 (
    echo [ERROR] Three invalid choices: exiting. Nothing more was deleted.
    set "RC=1"
    goto :end
)
goto :menu

:containers
docker compose rm -f
call :result "Stopped containers removed."
goto :menu

:images
docker image prune -f
call :result "Dangling images removed."
goto :menu

:networks
docker network prune -f
call :result "Unused networks removed."
goto :menu

:pgdata
call :drop_volume pgdata "DELETE DATABASE" postgres pg-backup backend
goto :menu

:pgadmin
call :drop_volume pgadmindata "DELETE PGADMIN" pgadmin
goto :menu

rem drop_volume <compose volume key> <confirmation phrase> <services using it...>
:drop_volume
set "KEY=%~1"
set "PHRASE=%~2"
rem The real volume name, whatever the project name is (name: in the file, COMPOSE_PROJECT_NAME or -p).
set "PROJECT="
for /f "tokens=2" %%n in ('docker compose config 2^>nul ^| findstr /b /c:"name:"') do set "PROJECT=%%n"
if not defined PROJECT (
    echo [FAILED] Could not read the project name from docker compose config. Nothing was deleted.
    set "RC=1"
    exit /b 0
)
set "VOL="
for /f "delims=" %%v in ('docker volume ls -q --filter "label=com.docker.compose.project=!PROJECT!" --filter "label=com.docker.compose.volume=!KEY!"') do set "VOL=%%v"
if not defined VOL (
    echo No volume "!KEY!" exists for project !PROJECT!. Nothing to delete.
    exit /b 0
)
echo.
echo   This permanently deletes the Docker volume !VOL!.
echo   Take a backup first if you need the data: README-Docker.md, section 9, "Backup".
set "ANSWER="
set /p "ANSWER=Type !PHRASE! to continue, anything else cancels: "
if not "!ANSWER!"=="!PHRASE!" (
    echo.
    echo Cancelled. Nothing was deleted.
    exit /b 0
)
shift
shift
:stop_users
if "%~1"=="" goto :remove
docker compose rm -sf %1
if errorlevel 1 (
    echo [FAILED] Could not stop and remove service %1, so the volume is still in use. Nothing was deleted.
    set "RC=1"
    exit /b 0
)
shift
goto :stop_users
:remove
docker volume rm "!VOL!"
call :result "Volume !VOL! deleted. It is created empty on the next start."
exit /b 0

:result
if errorlevel 1 (
    echo [FAILED] See the messages above.
    set "RC=1"
) else (
    echo [OK] %~1
)
exit /b 0

:end
popd
:done
call "%~dp0_common.bat" pause "%~f0"
exit /b %RC%
