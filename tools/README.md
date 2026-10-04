# 纹理生成工具

`build_textures.ps1` 从仓库内的 Tom JAR 读取纹理，再绘制本项目的访问器与高级漏斗纹理。需要 Windows PowerShell 和 System.Drawing，在仓库根目录运行：

```powershell
.\tools\build_textures.ps1
# 可指定相对于仓库根目录的 Tom JAR 路径
.\tools\build_textures.ps1 -TomJar 'libs/toms_storage_fabric-1.20-1.7.1.jar'
```

脚本覆盖 `src/main/resources/assets/digitalstorage/textures/block/` 中的 `advanced_inventory_hopper.png` 和 `digital_storage_accessor.png`，放大预览写入 `build/texture-previews/`。

日常构建直接使用仓库内的纹理，不需要先运行此脚本。
