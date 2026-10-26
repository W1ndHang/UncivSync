@echo off
rem 客户端 exe 构建入口 —— 2026-09-30 起由 deploy-client.py 统一负责：
rem 它会把 gradle 产物 jar 用 launch4j（src\launch4j-config.xml，自包含打包模式）
rem 嵌进 UncivSync.exe，部署到 Client\ 与 Client2\，并把游戏核心 jar 放到 Server\。
rem 这个 bat 只是个顺手入口，直接调 python。
setlocal
cd /d "%~dp0.."
python deploy-client.py %*
if errorlevel 1 (
    echo.
    echo [ERROR] deploy-client.py failed
    pause
    exit /b 1
)
