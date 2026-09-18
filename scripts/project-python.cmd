@echo off
rem Windows 版 project-python（对应 POSIX 的 scripts/project-python）。
rem
rem 原项目通过 ./scripts/project-python 调用 Python，该脚本是 POSIX sh，
rem 在 Windows 上无法执行（ProcessBuilder 不做 shell 展开，会直接报找不到文件）。
rem 本批处理保持同样的语义：优先使用项目内虚拟环境，缺失时回退到 PATH 上的 python。
rem
rem 用法：把 nailglow.python.bin 或环境变量 PYTHON_BIN 指向本文件即可。

setlocal

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."
set "LOCAL_PYTHON=%PROJECT_ROOT%\.venv\Scripts\python.exe"

if exist "%LOCAL_PYTHON%" (
    "%LOCAL_PYTHON%" %*
    exit /b %ERRORLEVEL%
)

python %*
exit /b %ERRORLEVEL%
