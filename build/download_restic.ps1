# 设置错误处理
$ErrorActionPreference = "Stop"
$ProgressPreference = 'SilentlyContinue'

$RESTIC_VERSION = "0.18.1"

# --- 1. 确保 7-Zip 已安装 ---
function Ensure-7Zip {
    if (Get-Command "7z" -ErrorAction SilentlyContinue) {
        return "7z"
    }

    $path7z = "${env:ProgramFiles}\7-Zip\7z.exe"
    if (Test-Path $path7z) {
        return $path7z
    }

    Write-Host "[-] 未找到 7-Zip，正在尝试通过 winget 安装..." -ForegroundColor Yellow
    try {
        # 使用 winget 静默安装 7-Zip
        Start-Process winget -ArgumentList "install --id 7zip.7zip --silent --accept-source-agreements --accept-package-agreements" -Wait
        if (Test-Path $path7z) { return $path7z }
    } catch {
        Write-Error "自动安装 7-Zip 失败，请手动安装: https://www.7-zip.org/"
    }

    throw "无法定位 7z 命令行工具。"
}

$Global:ZipExe = Ensure-7Zip

# --- 2. 下载与解压逻辑 ---
function Download-ResticBinary {
    param (
        [string]$resticArch,
        [string]$androidArch
    )

    $currentDir = Get-Location
    $targetPath = Join-Path $currentDir "source/app/src/main/jniLibs/$androidArch"

    if (-not (Test-Path $targetPath)) {
        New-Item -ItemType Directory -Path $targetPath -Force | Out-Null
    }

    $resticFile = "restic_${RESTIC_VERSION}_linux_${resticArch}.bz2"
    $url = "https://github.com/restic/restic/releases/download/v${RESTIC_VERSION}/$resticFile"
    $tempFile = Join-Path $targetPath $resticFile
    $finalFile = Join-Path $targetPath "librestic.so"

    Write-Host "`n[+] Processing: $androidArch" -ForegroundColor Cyan

    # 下载
    Write-Host "    Downloading..." -NoNewline
    Invoke-WebRequest -Uri $url -OutFile $tempFile
    Write-Host " Done." -ForegroundColor Green

    # 解压
    Write-Host "    Decompressing via 7-Zip..." -NoNewline

    # 7-Zip 解压 bz2 会产生一个去掉 .bz2 后缀的文件
    # -y: 自动确认, -so: 输出到标准输出 (类似 bzip2 -dc)
    try {
        if (Test-Path $finalFile) { Remove-Item $finalFile -Force }

        # 使用 & 调用变量存储的路径，并重定向输出流
        & $Global:ZipExe x "$tempFile" -so > "$finalFile"

        if ((Get-Item "$finalFile").Length -gt 1MB) {
            Write-Host " Success." -ForegroundColor Green
            Remove-Item $tempFile -Force
        } else {
            throw "解压文件损坏或体积异常。"
        }
    } catch {
        Write-Host " Failed." -ForegroundColor Red
        Write-Error $_
    }
}

# --- 3. 执行任务 ---
Download-ResticBinary "arm64" "arm64-v8a"
Download-ResticBinary "arm"   "armeabi-v7a"
Download-ResticBinary "amd64" "x86_64"
Download-ResticBinary "386"   "x86"

$ProgressPreference = 'Continue'
Write-Host "`n[!] 所有架构处理完毕。" -ForegroundColor Green