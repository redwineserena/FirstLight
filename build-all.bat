@echo off
setlocal
cd /d "%~dp0"

echo ==== building forge ====
pushd forge
call gradlew.bat build --no-daemon --console=plain
if errorlevel 1 (
    echo forge build failed
    popd
    exit /b 1
)
popd

echo ==== building neoforge ====
pushd neoforge
call gradlew.bat build --no-daemon --console=plain
if errorlevel 1 (
    echo neoforge build failed
    popd
    exit /b 1
)
popd

echo ==== building fabric ====
pushd fabric
call gradlew.bat build --no-daemon --console=plain
if errorlevel 1 (
    echo fabric build failed
    popd
    exit /b 1
)
popd

if not exist dist mkdir dist
copy /Y "forge\build\libs\introvideo-forge-1.0.0.jar" "dist\" >nul
copy /Y "neoforge\build\libs\introvideo-neoforge-1.0.0.jar" "dist\" >nul
copy /Y "fabric\build\libs\introvideo-fabric-1.0.0.jar" "dist\" >nul

echo.
echo done. jars are in dist\
exit /b 0
