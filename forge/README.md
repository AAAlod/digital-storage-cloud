# Forge 平台构建

Forge 1.20.1 / 47.4.10 使用独立 Gradle 构建，复用上级工程的 wrapper、版本属性和 common 源码选择。这样 ForgeGradle 与 Loom 不共享插件 classloader，也不建立第二份业务源码。

从仓库根目录使用 Java 17：

```powershell
.\gradlew.bat -p forge build --console=plain
```

当前处于移植阶段：共享源码编译通过不代表 Forge 功能或发行验收完成。平台注册、菜单、网络、能力库存和 Tom 集成在本目录接入；完整运行验收完成前不使用构建产物替换玩家安装。
