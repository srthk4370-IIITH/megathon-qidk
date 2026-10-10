# Installs the Windows Scheduled Task that runs the WSL ML supervisor every 5 minutes (hidden window),
# and makes WSL never idle-stop. Run once from a normal PowerShell. Idempotent.
$wslcfg = "$env:USERPROFILE\.wslconfig"
$cfg = Get-Content $wslcfg -Raw -ErrorAction SilentlyContinue
if ($cfg -notmatch "vmIdleTimeout") {
    $cfg = $cfg -replace "\[wsl2\]", "[wsl2]`r`nvmIdleTimeout=-1"
    Set-Content -Path $wslcfg -Value $cfg -Encoding ascii
}
if ($cfg -notmatch "instanceIdleTimeout") {
    Add-Content -Path $wslcfg -Value "`r`n[general]`r`ninstanceIdleTimeout=-1" -Encoding ascii
}
$tr = 'conhost.exe --headless wsl.exe -d Ubuntu -- bash /home/hp_srthk4370/iv/jobs/supervisor.sh'
schtasks /Create /F /SC MINUTE /MO 5 /TN IV_Supervisor /TR $tr | Out-Null
schtasks /Run /TN IV_Supervisor | Out-Null
schtasks /Query /TN IV_Supervisor /FO LIST | Select-String "TaskName|Status|Next Run"
