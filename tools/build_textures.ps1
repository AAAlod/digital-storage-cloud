param(
    [string]$TomJar = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression.FileSystem

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($TomJar)) {
    $dependencyRoot = Join-Path $projectRoot "libs"
    $jarFile = Get-ChildItem -LiteralPath $dependencyRoot -File |
        Where-Object { $_.Name -like "*toms_storage_fabric-1.20-1.7.1.jar" } |
        Select-Object -First 1
    if ($null -eq $jarFile) {
        throw "Tom's 1.7.1 jar was not found in $dependencyRoot"
    }
    $jarPath = $jarFile.FullName
} else {
    $jarPath = (Get-Item -LiteralPath (Join-Path $projectRoot $TomJar)).FullName
}
$textureRoot = Join-Path $projectRoot "src/main/resources/assets/digitalstorage/textures/block"
$previewRoot = Join-Path $projectRoot "build/texture-previews"
New-Item -ItemType Directory -Force -Path $textureRoot, $previewRoot | Out-Null

function Convert-HexColor([string]$hex) {
    $value = $hex.TrimStart('#')
    return [System.Drawing.Color]::FromArgb(
        255,
        [Convert]::ToInt32($value.Substring(0, 2), 16),
        [Convert]::ToInt32($value.Substring(2, 2), 16),
        [Convert]::ToInt32($value.Substring(4, 2), 16)
    )
}

function Get-ZipBitmap([string]$entryName) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $zip.GetEntry($entryName)
        if ($null -eq $entry) {
            throw "Missing texture in Tom's jar: $entryName"
        }
        $stream = $entry.Open()
        try {
            $temporary = [System.Drawing.Bitmap]::new($stream)
            try {
                return [System.Drawing.Bitmap]::new($temporary)
            } finally {
                $temporary.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
}

function Set-Pixel([System.Drawing.Bitmap]$image, [int]$x, [int]$y, [System.Drawing.Color]$color) {
    $image.SetPixel($x, $y, $color)
}

function Fill-Rect(
    [System.Drawing.Bitmap]$image,
    [int]$left,
    [int]$top,
    [int]$right,
    [int]$bottom,
    [System.Drawing.Color]$color
) {
    for ($y = $top; $y -le $bottom; $y++) {
        for ($x = $left; $x -le $right; $x++) {
            Set-Pixel $image $x $y $color
        }
    }
}

function Save-Preview([System.Drawing.Bitmap]$image, [string]$path) {
    $scale = 24
    $preview = [System.Drawing.Bitmap]::new($image.Width * $scale, $image.Height * $scale)
    $graphics = [System.Drawing.Graphics]::FromImage($preview)
    try {
        $graphics.Clear([System.Drawing.Color]::FromArgb(255, 24, 24, 24))
        for ($y = 0; $y -lt $image.Height; $y++) {
            for ($x = 0; $x -lt $image.Width; $x++) {
                $brush = [System.Drawing.SolidBrush]::new($image.GetPixel($x, $y))
                try {
                    $graphics.FillRectangle($brush, $x * $scale, $y * $scale, $scale, $scale)
                } finally {
                    $brush.Dispose()
                }
            }
        }
    } finally {
        $graphics.Dispose()
    }
    try {
        $preview.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $preview.Dispose()
    }
}

$cyanDark = Convert-HexColor "#176B73"
$cyan = Convert-HexColor "#31B9C3"
$cyanLight = Convert-HexColor "#65E1E6"
$darkest = Convert-HexColor "#211E19"
$dark = Convert-HexColor "#2B261F"
$darkLight = Convert-HexColor "#383229"
$bronzeDark = Convert-HexColor "#5D4925"
$bronze = Convert-HexColor "#8D681F"
$bronzeLight = Convert-HexColor "#A87226"

# Keep Tom's exact 16x16 hopper texture and add restrained status stripes only
# inside the two UV islands used by its original model.
$hopper = Get-ZipBitmap "assets/toms_storage/textures/block/inventory_hopper_basic.png"
try {
    Set-Pixel $hopper 2 4 $cyanDark
    Set-Pixel $hopper 3 4 $cyan
    Set-Pixel $hopper 4 4 $cyanLight
    Set-Pixel $hopper 8 5 $cyanDark
    Set-Pixel $hopper 9 5 $cyan
    Set-Pixel $hopper 10 5 $cyanLight
    Set-Pixel $hopper 11 5 $cyan
    Set-Pixel $hopper 12 5 $cyanDark
    Set-Pixel $hopper 13 5 $cyan

    $hopperPath = Join-Path $textureRoot "advanced_inventory_hopper.png"
    $hopper.Save($hopperPath, [System.Drawing.Imaging.ImageFormat]::Png)
    Save-Preview $hopper (Join-Path $previewRoot "advanced_inventory_hopper.png")
} finally {
    $hopper.Dispose()
}

# Build the storage casing from Tom's connector frame and reuse its palette,
# then add a compact inset storage grid and the matching cyan status marks.
$storage = Get-ZipBitmap "assets/toms_storage/textures/block/inventory_connector.png"
try {
    for ($y = 2; $y -le 13; $y++) {
        for ($x = 2; $x -le 13; $x++) {
            Set-Pixel $storage $x $y ($(if ((($x + $y) % 3) -eq 0) { $darkLight } else { $dark }))
        }
    }

    Fill-Rect $storage 3 3 12 12 $bronzeDark
    Fill-Rect $storage 4 4 11 11 $darkest
    Fill-Rect $storage 4 4 11 4 $bronze
    Fill-Rect $storage 4 11 11 11 $bronzeDark

    foreach ($point in @(
        @(5, 6), @(7, 6), @(9, 6),
        @(5, 8), @(7, 8), @(9, 8),
        @(5, 10), @(7, 10), @(9, 10)
    )) {
        Set-Pixel $storage $point[0] $point[1] $bronzeLight
        Set-Pixel $storage ($point[0] + 1) $point[1] $bronze
    }

    Set-Pixel $storage 2 6 $cyanDark
    Set-Pixel $storage 2 7 $cyan
    Set-Pixel $storage 2 8 $cyanLight
    Set-Pixel $storage 13 6 $cyanLight
    Set-Pixel $storage 13 7 $cyan
    Set-Pixel $storage 13 8 $cyanDark

    $storagePath = Join-Path $textureRoot "digital_storage_unit.png"
    $storage.Save($storagePath, [System.Drawing.Imaging.ImageFormat]::Png)
    Save-Preview $storage (Join-Path $previewRoot "digital_storage_unit.png")
} finally {
    $storage.Dispose()
}

Write-Output "Generated textures in $textureRoot"
Write-Output "Generated previews in $previewRoot"
