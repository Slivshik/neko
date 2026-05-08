param(
    [string]$LibWgGoDir = "libwg-go",
    [string]$OutRoot = "app\\executableSo"
)

$ErrorActionPreference = "Stop"

function Resolve-NdkRoot {
    if ($env:ANDROID_NDK_HOME -and (Test-Path (Join-Path $env:ANDROID_NDK_HOME "source.properties"))) {
        return $env:ANDROID_NDK_HOME
    }
    if ($env:ANDROID_HOME) {
        $candidate = Join-Path $env:ANDROID_HOME "ndk\\25.0.8775105"
        if (Test-Path (Join-Path $candidate "source.properties")) {
            return $candidate
        }
    }
    throw "ANDROID_NDK_HOME is not set or invalid. Please set ANDROID_NDK_HOME to your Android NDK path."
}

function Build-OneAbi {
    param(
        [string]$Abi,
        [string]$ArchName,
        [string]$TargetTriple,
        [string]$GoArch,
        [string]$ClangBin,
        [string]$NdkRoot,
        [string]$LibWgGoAbs,
        [string]$OutRootAbs
    )

    $toolchain = Join-Path $NdkRoot "toolchains\\llvm\\prebuilt\\windows-x86_64"
    $sysroot = Join-Path $toolchain "sysroot"
    $cc = Join-Path $toolchain ("bin\\" + $ClangBin)
    if (!(Test-Path $cc)) {
        throw "Compiler not found: $cc"
    }

    $env:ANDROID_ARCH_NAME = $ArchName
    $env:TARGET = $TargetTriple
    $env:GOARCH = $GoArch
    $env:GOOS = "android"
    $env:CGO_ENABLED = "1"
    $env:SYSROOT = $sysroot
    $env:CC = $cc
    $env:CFLAGS = ""
    $env:LDFLAGS = ""
    $env:ANDROID_PACKAGE_NAME = "io.nekohasekai.sagernet"

    $dest = Join-Path $OutRootAbs $Abi
    New-Item -ItemType Directory -Path $dest -Force | Out-Null

    Write-Host "Building libwg-go.so for $Abi ..."
    & go build -tags linux -ldflags "-checklinkname=0 -X golang.zx2c4.com/wireguard/ipc.socketDirectory=/data/data/$($env:ANDROID_PACKAGE_NAME)/cache/wireguard -buildid=" -v -trimpath -buildvcs=false -o (Join-Path $dest "libwg-go.so") -buildmode c-shared
    if ($LASTEXITCODE -ne 0) {
        throw "go build failed for $Abi"
    }
}

$repoRoot = (Resolve-Path ".").Path
$libWgGoAbs = (Resolve-Path $LibWgGoDir).Path
$outRootAbs = Join-Path $repoRoot $OutRoot
$ndkRoot = Resolve-NdkRoot

Push-Location $libWgGoAbs
try {
    Build-OneAbi -Abi "arm64-v8a" -ArchName "arm64" -TargetTriple "aarch64-linux-android21" -GoArch "arm64" -ClangBin "aarch64-linux-android21-clang.cmd" -NdkRoot $ndkRoot -LibWgGoAbs $libWgGoAbs -OutRootAbs $outRootAbs
    Build-OneAbi -Abi "armeabi-v7a" -ArchName "arm" -TargetTriple "armv7a-linux-androideabi21" -GoArch "arm" -ClangBin "armv7a-linux-androideabi21-clang.cmd" -NdkRoot $ndkRoot -LibWgGoAbs $libWgGoAbs -OutRootAbs $outRootAbs
    Build-OneAbi -Abi "x86_64" -ArchName "x86_64" -TargetTriple "x86_64-linux-android21" -GoArch "amd64" -ClangBin "x86_64-linux-android21-clang.cmd" -NdkRoot $ndkRoot -LibWgGoAbs $libWgGoAbs -OutRootAbs $outRootAbs
    Build-OneAbi -Abi "x86" -ArchName "x86" -TargetTriple "i686-linux-android21" -GoArch "386" -ClangBin "i686-linux-android21-clang.cmd" -NdkRoot $ndkRoot -LibWgGoAbs $libWgGoAbs -OutRootAbs $outRootAbs
}
finally {
    Pop-Location
}

Write-Host "libwg-go.so built for all ABI into $outRootAbs"
