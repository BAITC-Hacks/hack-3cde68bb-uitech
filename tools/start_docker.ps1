param([string]$EnvFile='.local/postgres.env')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$settings=if([System.IO.Path]::IsPathRooted($EnvFile)){$EnvFile}else{Join-Path $projectRoot $EnvFile}
if(-not(Get-Command docker -ErrorAction SilentlyContinue)){throw 'Install and start Docker Desktop first.'}
if(-not(Test-Path -LiteralPath $settings)){
    [void][System.IO.Directory]::CreateDirectory((Split-Path $settings -Parent))
    $randomBytes=New-Object byte[] 32
    $rng=[System.Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($randomBytes)}finally{$rng.Dispose()}
    $password=[Convert]::ToBase64String($randomBytes)
    [System.IO.File]::WriteAllText($settings,"POSTGRES_DB=uitech`nPOSTGRES_USER=uitech`nPOSTGRES_PORT=5433`nPOSTGRES_PASSWORD=$password`nBACKEND_PORT=8080`nUI_PORT=5173`n")
    Write-Host 'Created local PostgreSQL settings with a random password.'
}
Push-Location $projectRoot
try{
    & docker compose --env-file $settings up --build -d --wait --wait-timeout 180
    if($LASTEXITCODE -ne 0){throw 'Docker startup failed. See docker compose logs for details.'}
    $uiPort='5173'
    foreach($line in Get-Content -LiteralPath $settings){if($line -match '^UI_PORT=(\d+)$'){$uiPort=$Matches[1]}}
    if($env:UI_PORT){$uiPort=$env:UI_PORT}
    Write-Host "Application ready: http://127.0.0.1:$uiPort"
}finally{Pop-Location}
