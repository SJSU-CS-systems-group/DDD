@echo off
REM Check if pull.rebase is true in the local repo
for /f "usebackq delims=" %%A in (`git config --get pull.rebase`) do set VALUE=%%A

if "%VALUE%"=="true" (
    echo pull.rebase is set to true
    exit /b 0
) else (
    echo pull.rebase is NOT set to true
    echo run
    echo     git config pull.rebase true
    exit /b 1
)

