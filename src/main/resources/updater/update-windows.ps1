# Managerの終了後に、ダウンロードして検証済みのインストーラーでManagerを上書き更新し、再起動する。
#
# jpackageのMSIは、上書き更新のときにインストール先のフォルダ全体を削除する。
# Managerのデータ（設定、プロファイル、SSH、オフラインデータなど）はインストール先と同じ
# フォルダにあるため、インストールの前に退避し、インストールのあとで復元する。
#
# 結果は、成功と失敗のどちらでも$ResultFileへJSONで書き、Managerを再起動する。
param(
    [Parameter(Mandatory)][int]$ProcessId,
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$DataDir,
    [Parameter(Mandatory)][string]$AppExe,
    [Parameter(Mandatory)][string]$ResultFile,
    [Parameter(Mandatory)][string]$Version
)

$ErrorActionPreference = 'Stop'
$success = $false
$exitCode = $null
$message = $null
$backup = Join-Path ([IO.Path]::GetTempPath()) ('SushiEricServerManager-update-' + [Guid]::NewGuid().ToString('N'))

# インストールで置き換わる項目。これ以外は、データとして退避する。
$replaced = @('app', 'runtime', 'updates', 'lock', [IO.Path]::GetFileName($AppExe))

try {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($process -and -not $process.WaitForExit(60000)) {
        throw 'Managerが終了しなかったため、更新を中止しました。'
    }

    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    Get-ChildItem -LiteralPath $DataDir -Force |
        Where-Object { $replaced -notcontains $_.Name } |
        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $backup -Recurse -Force }

    $install = Start-Process -FilePath $Installer -ArgumentList '/qn' -Wait -PassThru
    $exitCode = $install.ExitCode
    # 0は成功、1641と3010は成功して再起動が必要であることを表す。
    if (@(0, 1641, 3010) -contains $exitCode) {
        $success = $true
    } else {
        $message = "インストーラーが失敗しました（終了コード $exitCode）。"
    }
} catch {
    $message = $_.Exception.Message
}

# データの復元は、インストールの成功と失敗のどちらでも行う。
try {
    if (Test-Path -LiteralPath $backup) {
        New-Item -ItemType Directory -Path $DataDir -Force | Out-Null
        $items = @(Get-ChildItem -LiteralPath $backup -Force)
        foreach ($item in $items) {
            Copy-Item -LiteralPath $item.FullName -Destination $DataDir -Recurse -Force
        }
        foreach ($item in $items) {
            if (-not (Test-Path -LiteralPath (Join-Path $DataDir $item.Name))) {
                throw "データを復元できませんでした: $($item.Name)"
            }
        }
        Remove-Item -LiteralPath $backup -Recurse -Force
    }
} catch {
    # 復元に失敗した場合は、退避したデータを残す。
    $success = $false
    $restoreMessage = "データの復元に失敗しました。退避先: $backup（$($_.Exception.Message)）"
    $message = (@($message, $restoreMessage) | Where-Object { $_ }) -join ' '
}

try {
    New-Item -ItemType Directory -Path (Split-Path -Parent $ResultFile) -Force | Out-Null
    $result = [ordered]@{
        success   = $success
        version   = $Version
        exitCode  = $exitCode
        message   = $message
        timestamp = [DateTime]::UtcNow.ToString('o')
    }
    # BOMなしのUTF-8で書く。
    [IO.File]::WriteAllText($ResultFile, ($result | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
} catch {
    # 結果を書けなくても、Managerの再起動は続ける。
}

if (Test-Path -LiteralPath $AppExe) {
    Start-Process -FilePath $AppExe
}
