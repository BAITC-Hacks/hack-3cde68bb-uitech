param([string]$BinDirectory='.local/postgres-runtime/bin',[string]$DataDirectory='.local/postgres-data',[string]$EnvFile='.local/postgres.env')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
function AbsoluteProjectPath([string]$value){if([System.IO.Path]::IsPathRooted($value)){return $value};return Join-Path $projectRoot $value}
$pgBin=AbsoluteProjectPath $BinDirectory
$pgData=AbsoluteProjectPath $DataDirectory
$settings=AbsoluteProjectPath $EnvFile
if(-not (Test-Path (Join-Path $pgBin 'postgres.exe'))){throw 'PostgreSQL binaries not found. Supply -BinDirectory pointing to PostgreSQL 17/bin, or use Docker Compose.'}
$values=@{}
foreach($line in Get-Content -LiteralPath $settings){if($line -match '^(POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD|POSTGRES_PORT)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
$dbName=$values.POSTGRES_DB;$dbUser=$values.POSTGRES_USER;$dbPort=$values.POSTGRES_PORT
if($dbName -notmatch '^[a-z][a-z0-9_]*$' -or $dbUser -notmatch '^[a-z][a-z0-9_]*$' -or $dbPort -notmatch '^\d{1,5}$' -or -not $values.POSTGRES_PASSWORD){throw 'Invalid or incomplete PostgreSQL env settings'}
if(-not(Test-Path (Join-Path $pgData 'PG_VERSION'))){
    $pwFile=Join-Path $projectRoot ('.local/initdb-'+[guid]::NewGuid().ToString('N')+'.tmp')
    try{
        [System.IO.File]::WriteAllText($pwFile,$values.POSTGRES_PASSWORD)
        & (Join-Path $pgBin 'initdb.exe') -D $pgData -U $dbUser --pwfile=$pwFile --auth=scram-sha-256 --encoding=UTF8 --locale=C
        if($LASTEXITCODE -ne 0){throw 'initdb failed'}
        "CREATE DATABASE $dbName;" | & (Join-Path $pgBin 'postgres.exe') --single -D $pgData postgres
        if($LASTEXITCODE -ne 0){throw 'Database creation failed'}
    }finally{if(Test-Path -LiteralPath $pwFile){Remove-Item -LiteralPath $pwFile}}
}
& (Join-Path $pgBin 'pg_ctl.exe') -D $pgData status
if($LASTEXITCODE -eq 0){exit 0}
$pgLog=Join-Path $projectRoot '.local/postgres-native.log'
& (Join-Path $pgBin 'pg_ctl.exe') -D $pgData -l $pgLog -o "-h 127.0.0.1 -p $dbPort -c shared_buffers=32MB -c max_connections=20 -c max_wal_size=128MB -c min_wal_size=32MB" -w -t 30 start
exit $LASTEXITCODE
