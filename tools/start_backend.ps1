param(
    [ValidateSet('postgres','file')][string]$Storage='postgres',
    [string]$EnvFile='.local/postgres.env',
    [switch]$MigrateLocal,
    [int]$Port=8080
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if($Storage -eq 'postgres') {
    $settings=Join-Path $projectRoot $EnvFile
    if(Test-Path -LiteralPath $settings) {
        $values=@{}
        foreach($line in Get-Content -LiteralPath $settings) {
            if($line -match '^(POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD|POSTGRES_PORT)=(.*)$') {$values[$Matches[1]]=$Matches[2]}
        }
        if(-not $env:UITECH_DB_URL){$dbPort=if($values.POSTGRES_PORT){$values.POSTGRES_PORT}else{'5433'};$dbName=if($values.POSTGRES_DB){$values.POSTGRES_DB}else{'uitech'};$env:UITECH_DB_URL="jdbc:postgresql://127.0.0.1:${dbPort}/${dbName}?reWriteBatchedInserts=true"}
        if(-not $env:UITECH_DB_USER){$env:UITECH_DB_USER=$values.POSTGRES_USER}
        if(-not $env:UITECH_DB_PASSWORD){$env:UITECH_DB_PASSWORD=$values.POSTGRES_PASSWORD}
    }
    if(-not $env:UITECH_DB_PASSWORD){throw 'Set UITECH_DB_PASSWORD or provide a local env file. Do not commit passwords.'}
}
$env:SPRING_PROFILES_ACTIVE=$Storage
$env:PORT="$Port"
$env:UITECH_MIGRATE_LOCAL=if($MigrateLocal){'true'}else{'false'}
$env:UITECH_DATA_DIR=Join-Path $projectRoot '.local/backend-data'
$env:UITECH_FILES_DIR=Join-Path $projectRoot '.local/files'
Push-Location (Join-Path $projectRoot 'backend')
try { & java -Xmx512m -jar target/replenishment-0.1.0.jar; exit $LASTEXITCODE } finally { Pop-Location }
