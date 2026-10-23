param(
  [ValidateSet('Cpu', 'Cuda')]
  [string] $Backend = 'Cpu'
)

$ErrorActionPreference = 'Stop'
$workspacePath = [System.IO.Path]::GetFullPath($PSScriptRoot)
$downloadDirectory = Join-Path $workspacePath 'downloads'
$nativeName = if ($Backend -eq 'Cuda') { 'llama-b11669-win-cuda-12.4-x64' } else { 'b11669-cpu' }
$nativeDirectory = Join-Path $workspacePath "natives/$nativeName"
New-Item -ItemType Directory -Force $downloadDirectory, $nativeDirectory | Out-Null

$packages = if ($Backend -eq 'Cuda') {
  @(
    @{Name = 'llama-b11669-bin-win-cuda-12.4-x64.zip'; Hash = 'c2da1f73ed176ae093ac5aceb978a7b167a16113f6d74c6789eeaee2135cb8dc'},
    @{Name = 'cudart-llama-bin-win-cuda-12.4-x64.zip'; Hash = '8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6'}
  )
} else {
  @{Name = 'llama-b11669-bin-win-cpu-x64.zip'; Hash = '37b3efcf962847ca869745fd4ccf7f5e7e8dacfeff9fa3523b4ca8cdc66d632b'}
}

foreach ($package in $packages) {
  $packageName = $package.Name
  $expectedHash = $package.Hash
  $archivePath = Join-Path $downloadDirectory $packageName
  if (!(Test-Path -LiteralPath $archivePath -PathType Leaf)) {
    $partialPath = "$archivePath.part"
    $downloadUri = "https://github.com/ggml-org/llama.cpp/releases/download/b11669/$packageName"
    Write-Output "Downloading $packageName"
    Invoke-WebRequest -Uri $downloadUri -OutFile $partialPath
    if ((Get-FileHash -LiteralPath $partialPath -Algorithm SHA256).Hash -ne $expectedHash) {
      throw "SHA-256 mismatch: $partialPath"
    }
    $checkedPartialPath = [System.IO.Path]::GetFullPath($partialPath)
    $checkedArchivePath = [System.IO.Path]::GetFullPath($archivePath)
    if (!$checkedPartialPath.StartsWith($downloadDirectory + [System.IO.Path]::DirectorySeparatorChar) -or
        !$checkedArchivePath.StartsWith($downloadDirectory + [System.IO.Path]::DirectorySeparatorChar)) {
      throw 'Download path check failed'
    }
    Move-Item -LiteralPath $checkedPartialPath -Destination $checkedArchivePath
  }
  if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $expectedHash) {
    throw "SHA-256 mismatch: $archivePath; existing file retained"
  }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
  try {
    foreach ($entry in $archive.Entries) {
      $destination = [System.IO.Path]::GetFullPath((Join-Path $nativeDirectory $entry.FullName))
      if (!$destination.StartsWith($nativeDirectory + [System.IO.Path]::DirectorySeparatorChar)) {
        throw "Archive path check failed: $($entry.FullName)"
      }
      if ($entry.FullName.EndsWith('/')) {
        New-Item -ItemType Directory -Force $destination | Out-Null
        continue
      }
      $entryStream = $entry.Open()
      $digest = [System.Security.Cryptography.SHA256]::Create()
      try {
        $entryHash = [System.BitConverter]::ToString($digest.ComputeHash($entryStream)).Replace('-', '')
      } finally {
        $entryStream.Dispose()
        $digest.Dispose()
      }
      if ((Test-Path -LiteralPath $destination -PathType Leaf) -and
          (Get-Item -LiteralPath $destination).Length -eq $entry.Length -and
          (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $entryHash) {
        continue
      }
      New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
    }
  } finally {
    $archive.Dispose()
  }
}

Write-Output "Native runtime ready: $nativeDirectory"
Write-Output 'Build: .\mvnw.cmd clean verify'
Write-Output 'Check: .\run-check.cmd'
