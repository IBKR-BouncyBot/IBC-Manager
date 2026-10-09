<#
Thanks to Copilot for generating this script.
#>
param(
    [string]$Install4J
)

$confPath = Join-Path $Install4J "i4jparams.conf"
$line = Select-String -Path $confPath -Pattern '<variable name="javaOptions"' | Select-Object -First 1
$EXTRA_JAVA_OPTIONS = $line.Line -replace '.*value="', '' -replace '".*', ''

Write-Output $EXTRA_JAVA_OPTIONS
