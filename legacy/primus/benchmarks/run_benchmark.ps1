# B-002 Stage 4 Benchmark Runner
# Sets up DLL paths so the CUDA-enabled llama-cpp-python can load, then runs both benchmarks.

$ErrorActionPreference = "Stop"
$CUDA_RT  = "C:\Users\ASUS\AppData\Local\Programs\Python\Python312\Lib\site-packages\nvidia\cuda_runtime\bin"
$CUBLAS   = "C:\Users\ASUS\AppData\Local\Programs\Python\Python312\Lib\site-packages\nvidia\cublas\bin"
$CUDA_NV  = "C:\Users\ASUS\AppData\Local\Programs\Python\Python312\Lib\site-packages\nvidia\cuda_nvrtc\bin"
$LLAMA    = "C:\Users\ASUS\AppData\Local\Programs\Python\Python312\Lib\site-packages\llama_cpp\lib"

$env:PATH = "$CUDA_RT;$CUBLAS;$CUDA_NV;$LLAMA;" + $env:PATH

Set-Location $PSScriptRoot

Write-Host "=== Running Performance Benchmark ===" -ForegroundColor Cyan
python benchmark_perf.py
if ($LASTEXITCODE -ne 0) { Write-Host "Performance benchmark failed." -ForegroundColor Red; exit 1 }

Write-Host "`n=== Running Quality Benchmark ===" -ForegroundColor Cyan
python benchmark_quality.py
if ($LASTEXITCODE -ne 0) { Write-Host "Quality benchmark failed." -ForegroundColor Red; exit 1 }

Write-Host "`n=== All benchmarks complete. Results in benchmarks/results/ ===" -ForegroundColor Green
