$ErrorActionPreference = 'Stop'
$apk = 'C:\Users\admin\Desktop\GuardianLink-debug.apk'
$shell = New-Object -ComObject Shell.Application
$drives = $shell.NameSpace(17)
$phone = $drives.Items() | Where-Object { $_.Name -match 'HUAWEI|nova' } | Select-Object -First 1
if (-not $phone) { Write-Output 'PHONE_NOT_FOUND'; exit 1 }
Write-Output ('PHONE: ' + $phone.Name)
$items = $phone.GetFolder.Items()
$storage = $items | Where-Object { $_.Name -match 'Internal|Storage' } | Select-Object -First 1
if (-not $storage) { $storage = $items | Select-Object -First 1 }
if (-not $storage) { Write-Output 'STORAGE_NOT_FOUND'; $items | ForEach-Object { Write-Output ('  child: ' + $_.Name) }; exit 1 }
$dl = $storage.GetFolder.Items() | Where-Object { $_.Name -match 'Download|download' } | Select-Object -First 1
if (-not $dl) { Write-Output 'DOWNLOAD_NOT_FOUND'; $storage.GetFolder.Items() | ForEach-Object { Write-Output ('  child: ' + $_.Name) }; exit 1 }
$dest = $dl.GetFolder
Write-Output ('DEST: ' + $storage.Name + ' / ' + $dl.Name)
$dest.CopyHere($apk, 16)
Start-Sleep -Seconds 25
$found = $dest.Items() | Where-Object { $_.Name -like 'GuardianLink*' }
if ($found) {
    $found | ForEach-Object { Write-Output ('COPIED: ' + $_.Name + ' size=' + $_.Size) }
} else {
    Write-Output 'COPY_PENDING_OR_FAILED'
}
