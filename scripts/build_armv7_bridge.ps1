param([Parameter(Mandatory=$true)][string]$NdkRoot)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$toolchain=Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64'
$compiler=Join-Path $toolchain 'bin/clang.exe'
$sysroot=Join-Path $toolchain 'sysroot'
$source=Join-Path $projectRoot 'app/src/main/cpp/echo_node.c'
$output=Join-Path $projectRoot 'app/src/tv32/jniLibs/armeabi-v7a/libecho_node.so'
& $compiler --target=armv7a-linux-androideabi23 "--sysroot=$sysroot" -fPIC -shared '-Wl,-z,max-page-size=16384' -o $output $source -ldl
if($LASTEXITCODE -ne 0){throw 'ARMv7 JNI bridge compilation failed'}
$compatSource=Join-Path $projectRoot 'app/src/main/cpp/echo_api23.c'
$compatOutput=Join-Path $projectRoot 'app/src/tv32/jniLibs/armeabi-v7a/libecho_compat.so'
& $compiler --target=armv7a-linux-androideabi23 "--sysroot=$sysroot" -fPIC -shared '-Wl,-z,max-page-size=16384' '-Wl,-z,global' -o $compatOutput $compatSource -ldl
if($LASTEXITCODE -ne 0){throw 'API23 compatibility library compilation failed'}
