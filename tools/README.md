# Texture tool

`build_textures.ps1` regenerates two block textures from the checked-in Tom's
Storage JAR and adds this project's design elements. Run on Windows with
PowerShell and System.Drawing available, from the repository root:

```powershell
.\tools\build_textures.ps1
# Optional dependency path, relative to the repository root:
.\tools\build_textures.ps1 -TomJar 'libs/toms_storage_fabric-1.20-1.7.1.jar'
```

The script overwrites `advanced_inventory_hopper.png` and
`digital_storage_unit.png` under
`src/main/resources/assets/digitalstorage/textures/block/`, and writes enlarged
previews to `build/texture-previews/`. It is an authoring tool, not a required
build step; building uses the textures already in the repository.
