@echo off
rem FarmBot one-key deploy: build -> install -> re-grant accessibility + runtime perms
rem Usage: tools\deploy.bat [device-serial]
setlocal
set PKG=com.farmbot.assistant
set SVC=%PKG%/%PKG%.bot.BotService
set APK=app\build\outputs\apk\debug\app-debug.apk
set SERIAL=%~1

if not defined SERIAL (
  for /f "skip=1 tokens=1" %%d in ('adb devices') do (
    if not defined SERIAL if not "%%d"=="" set SERIAL=%%d
  )
)
if not defined SERIAL (
  echo [error] no adb device found. plug in a device or pass serial as argument.
  exit /b 1
)
echo [device] %SERIAL%

echo [1/3] build
call gradle assembleDebug --console=plain
if errorlevel 1 exit /b 1

echo [2/3] install
adb -s %SERIAL% install -r %APK%
if errorlevel 1 exit /b 1

echo [3/3] re-grant accessibility + runtime permissions
adb -s %SERIAL% shell settings put secure enabled_accessibility_services %SVC%
adb -s %SERIAL% shell settings put secure accessibility_enabled 1
adb -s %SERIAL% shell pm grant --user 0 %PKG% android.permission.POST_NOTIFICATIONS
adb -s %SERIAL% shell appops set %PKG% SYSTEM_ALERT_WINDOW allow

echo [done] %PKG% deployed to %SERIAL%
endlocal
