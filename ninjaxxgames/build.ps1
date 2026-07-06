# Build script NinjaxxGames - compile + package le .jar sans Maven
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$m2   = "$env:USERPROFILE\.m2\repository"

# --- JDK 21 (Paper 1.21.1 exige Java 21) ---
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium\' -Directory |
       Where-Object Name -like 'jdk-21*' | Select-Object -First 1
if (-not $jdk) { throw "JDK 21 introuvable. Installe-le : winget install EclipseAdoptium.Temurin.21.JDK" }
$javac = Join-Path $jdk.FullName 'bin\javac.exe'
$jar   = Join-Path $jdk.FullName 'bin\jar.exe'

# --- Classpath : paper-api + placeholderapi + noteblockapi + Adventure 4.17.0 ---
$cp = @(
  "$m2\io\papermc\paper\paper-api\1.21.1-R0.1-SNAPSHOT\paper-api-1.21.1-R0.1-SNAPSHOT.jar"
  "$m2\me\clip\placeholderapi\2.11.6\placeholderapi-2.11.6.jar"
  "$m2\com\xxmicloxx\NoteBlockAPI\1.6.4-SNAPSHOT\NoteBlockAPI-1.6.4-20250430.183516-1.jar"
  "$m2\net\md-5\bungeecord-chat\1.21-R0.2-deprecated+build.21\bungeecord-chat-1.21-R0.2-deprecated+build.21.jar"
)
# Ajoute tous les jars Adventure 4.17.0 + dépendances (examination, option)
$cp += Get-ChildItem "$m2\net\kyori" -Recurse -Filter '*.jar' |
       Where-Object { $_.Name -notlike '*-sources.jar' -and
                      ($_.FullName -like '*4.17.0*' -or $_.FullName -like '*examination*1.3.0*' -or $_.FullName -like '*option*1.1.0*') } |
       Select-Object -ExpandProperty FullName
$classpath = $cp -join ';'

# --- Dossiers de sortie ---
$out = Join-Path $root 'target\classes'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# --- Compilation ---
Write-Host "Compilation avec $($jdk.Name)..." -ForegroundColor Cyan
$sources = Get-ChildItem "$root\src\main\java" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$sourcesFile = Join-Path $env:TEMP 'ninjaxx_sources.txt'
[System.IO.File]::WriteAllLines($sourcesFile, $sources, (New-Object System.Text.UTF8Encoding($false)))
$logFile = Join-Path $env:TEMP 'ninjaxx_javac.log'
$ErrorActionPreference = 'Continue'
& $javac -encoding UTF-8 -d $out -classpath $classpath "@$sourcesFile" 2>$logFile
$javacExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
Get-Content $logFile | Where-Object { $_ -match 'error:|warning:' } | Select-Object -First 5
if ($javacExit -ne 0) { throw "Echec de la compilation (voir $logFile)." }

# --- Copie des ressources (plugin.yml, config.yml, etc.) ---
Copy-Item "$root\src\main\resources\*" -Destination $out -Recurse -Force

# --- Packaging du .jar ---
$jarName = Join-Path $root 'target\NinjaxxGames-1.0.0.jar'
$ErrorActionPreference = 'Continue'
& $jar --create --file $jarName -C $out .
$jarExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($jarExit -ne 0) { throw "Echec de la creation du .jar." }

Write-Host "OK -> $jarName" -ForegroundColor Green
