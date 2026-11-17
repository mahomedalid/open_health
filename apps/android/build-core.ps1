param(
    [string]$NdkPath = $env:ANDROID_NDK_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $NdkPath -or -not (Test-Path $NdkPath)) {
    throw 'Set ANDROID_NDK_HOME to an installed Android NDK directory or pass -NdkPath.'
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$toolchain = Join-Path $NdkPath 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$clang = Join-Path $toolchain 'aarch64-linux-android31-clang.cmd'
$ar = Join-Path $toolchain 'llvm-ar.exe'
if (-not (Test-Path $clang) -or -not (Test-Path $ar)) {
    throw "Android NDK compiler or archiver not found under $toolchain"
}

$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang
$env:CC_aarch64_linux_android = $clang
$env:AR_aarch64_linux_android = $ar
# UniFFI reads binding metadata from exported symbols, which `strip = true` removes.
$env:CARGO_PROFILE_RELEASE_STRIP = 'debuginfo'
$target = 'aarch64-linux-android'
$generated = Join-Path $PSScriptRoot 'app\build\generated'
$bindings = Join-Path $generated 'uniffi'
$native = Join-Path $generated 'jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $bindings, $native | Out-Null

Push-Location $repo
try {
    & cargo build --locked --release --target $target -p oura-core --lib
    if ($LASTEXITCODE -ne 0) { throw 'Android Rust core build failed.' }
    $library = Join-Path $repo "target\$target\release\liboura_core.so"
    & cargo run --locked --release -p oura-core --bin uniffi-bindgen -- generate --library $library --language kotlin --out-dir $bindings
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin binding generation failed.' }
    if (-not (Get-ChildItem $bindings -Recurse -Filter '*.kt' -ErrorAction SilentlyContinue)) {
        throw 'UniFFI produced no Kotlin bindings; the core library may be missing metadata.'
    }
    Copy-Item $library (Join-Path $native 'liboura_core.so') -Force
} finally {
    Pop-Location
}
