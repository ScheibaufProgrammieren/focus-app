Write-Host "=============================================" -ForegroundColor Green
Write-Host "       FocusGuard GitHub Sync Helper" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
Write-Host ""
Write-Host "This script helper will prepare your code to be pushed to GitHub."
Write-Host "This allows GitHub Actions to compile the APK in the cloud for free."
Write-Host ""

# Check if git is installed
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "[Error] Git is not installed on your system." -ForegroundColor Red
    Write-Host "Please install Git from https://git-scm.com/ and run this script again." -ForegroundColor Yellow
    Exit
}

# Initialize repository if not already done
if (-not (Test-Path .git)) {
    Write-Host "Initializing Git Repository..." -ForegroundColor Cyan
    git init
    git branch -M main
}

# Create .gitignore to prevent pushing build caches
if (-not (Test-Path .gitignore)) {
    Write-Host "Creating .gitignore..." -ForegroundColor Cyan
    $gitignore = @"
.gradle
/local.properties
/.idea/caches
/.idea/workspace.xml
/.idea/gradle.xml
/.idea/assetWizardSettings.xml
.DS_Store
/build
/app/build
*.iml
*.apk
"@
    Set-Content -Path .gitignore -Value $gitignore
}

# Stage and commit
Write-Host "Staging and committing files..." -ForegroundColor Cyan
git add .
git commit -m "Initialize FocusGuard code & workflows"

Write-Host ""
Write-Host "====================== SUCCESS ======================" -ForegroundColor Green
Write-Host "Your files are committed locally!" -ForegroundColor Green
Write-Host ""
Write-Host "Now run the following commands to link and push to your GitHub:" -ForegroundColor Yellow
Write-Host "1. Create a new repository on https://github.com (Keep it Private or Public)" -ForegroundColor White
Write-Host "2. Copy the repository URL (e.g., https://github.com/yourusername/focusguard.git)" -ForegroundColor White
Write-Host "3. Run this command in your terminal:" -ForegroundColor Yellow
Write-Host "   git remote add origin <PASTE_YOUR_REPOSITORY_URL>" -ForegroundColor White
Write-Host "4. Run this command to upload the code:" -ForegroundColor Yellow
Write-Host "   git push -u origin main" -ForegroundColor White
Write-Host ""
Write-Host "Once pushed, go to the 'Actions' tab on your GitHub repository page."
Write-Host "GitHub will build the APK and make it available for download."
Write-Host "=====================================================" -ForegroundColor Green
