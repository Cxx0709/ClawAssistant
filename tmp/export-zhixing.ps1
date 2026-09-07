param([string]$InputPath = '', [string]$OutputPath = '')
$ErrorActionPreference = 'Stop'
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    $reportPath = (Get-ChildItem -LiteralPath 'D:\Temp\ClawAssistant' -Filter '*.docx' | Select-Object -First 1).FullName
    if ($InputPath) { $reportPath = $InputPath }
    $pdfPath = [System.IO.Path]::ChangeExtension($reportPath, '.pdf')
    if ($OutputPath) { $pdfPath = $OutputPath }
    $report = $word.Documents.Open($reportPath, $false, $true)
    $report.Repaginate()
    $report.ExportAsFixedFormat($pdfPath, 17)
    Write-Output ('Pages: ' + $report.ComputeStatistics(2))
    $report.Close(0)
} finally {
    $word.Quit()
}
